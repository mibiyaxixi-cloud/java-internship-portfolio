package com.bookflow.reservation;

import com.bookflow.catalog.*;
import com.bookflow.waitlist.WaitlistCoordinator;
import com.bookflow.waitlist.WaitlistMapper;
import com.bookflow.common.BusinessException;
import com.bookflow.common.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private final CatalogMapper catalog;
    private final ReservationMapper reservations;
    private final Clock clock;
    private final WaitlistCoordinator coordinator;
    private final WaitlistMapper queue;

    public ReservationService(CatalogMapper catalog, ReservationMapper reservations, Clock clock, WaitlistCoordinator coordinator, WaitlistMapper queue) {
        this.catalog = catalog;
        this.reservations = reservations;
        this.clock = clock;
        this.coordinator = coordinator;
        this.queue = queue;
    }

    public List<ReservationView> mine(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return reservations.findMine(userId).stream().map(row -> row.view(now)).toList();
    }

    // 所有排班写操作遵循设备→时段→预约的锁顺序。锁持续到整个事务提交/回滚。
    // READ_COMMITTED 避免等待设备锁前的定位查询留下旧快照；不能用内存 synchronized 替代。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationView reserve(long userId, long slotId) {
        SlotRow reference = requireSlot(slotId);
        Device device = catalog.lockDevice(reference.deviceId());
        SlotRow slot = catalog.lockSlot(slotId);
        if (device == null || slot == null) throw new NotFoundException("设备或时段已删除");
        LocalDateTime now = LocalDateTime.now(clock);
        if (!slot.startAt().isAfter(now)) throw BusinessException.conflict("SLOT_STARTED", "时段已开始或已结束，不能预约");
        if (!"ACTIVE".equals(device.status()) || !"OPEN".equals(slot.status())) {
            throw BusinessException.conflict("SLOT_CLOSED", "设备或时段未开放");
        }
        if (slot.occupied()) throw occupied();
        if (queue.liveCount(slotId) > 0) throw BusinessException.conflict("WAITLIST_PRIORITY", "已有候补排队，名额将按顺序递补");
        try {
            reservations.insert(userId, slotId);
        } catch (DuplicateKeyException exception) {
            // uk_reservation_active_slot 是最终防线；将约束冲突解释为业务失败。
            // 异常向事务代理抛出，不能吞掉后继续提交。
            throw occupied();
        }
        return reservations.findDetail(reservations.lastInsertId()).view(now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationView cancel(long userId, long id) {
        ReservationRecord reference = reservations.findRecord(id);
        if (reference == null) throw new NotFoundException("预约不存在");
        requireOwner(reference, userId);
        SlotRow originalSlot = requireSlot(reference.slotId());
        Device device = catalog.lockDevice(originalSlot.deviceId());
        SlotRow slot = catalog.lockSlot(originalSlot.id());
        ReservationRecord reservation = reservations.lockRecord(id);
        if (reservation == null || slot == null) throw new NotFoundException("预约或时段不存在");
        requireOwner(reservation, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        // 重复取消不再写库，即使后来已过开始时间，也可返回原取消结果。
        if ("CANCELLED".equals(reservation.status())) return reservations.findDetail(id).view(now);
        if (!"CONFIRMED".equals(reservation.status())) throw BusinessException.conflict("NOT_CONFIRMED", "限时资格请到我的候补处理；已过期记录不能取消");
        if (!slot.startAt().isAfter(now)) throw BusinessException.conflict("SLOT_STARTED", "时段开始后不能取消");
        if (reservations.cancel(id, userId, now) != 1) {
            throw BusinessException.conflict("RESERVATION_CHANGED", "预约状态已变化，请刷新记录");
        }
        // 取消、释放占用、向下一人提供资格及登记超时任务必须共同提交。
        coordinator.advanceLocked(device, slot, now);
        // 更新状态和释放生成列占用是同一行、同一个事务，历史记录不删除。
        return reservations.findDetail(id).view(now);
    }

    private SlotRow requireSlot(long id) {
        if (id <= 0) throw BusinessException.invalid("时段 ID 必须为正整数");
        SlotRow slot = catalog.findSlot(id);
        if (slot == null) throw new NotFoundException("时段不存在");
        return slot;
    }

    private void requireOwner(ReservationRecord record, long userId) {
        if (record.userId() != userId) throw new BusinessException(403, "FORBIDDEN", "只能操作自己的预约");
    }

    private BusinessException occupied() {
        return BusinessException.conflict("SLOT_OCCUPIED", "该时段已被预约，请选择其他时段");
    }
}
