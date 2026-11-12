package com.bookflow.waitlist;

import com.bookflow.catalog.CatalogMapper;
import com.bookflow.catalog.Device;
import com.bookflow.catalog.SlotRow;
import com.bookflow.common.BusinessException;
import com.bookflow.common.NotFoundException;
import com.bookflow.reservation.ReservationMapper;
import com.bookflow.reservation.ReservationRecord;
import com.bookflow.reservation.ReservationView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistService {
    private final CatalogMapper catalog;
    private final WaitlistMapper queue;
    private final ReservationMapper reservations;
    private final WaitlistCoordinator coordinator;
    private final Clock clock;
    public WaitlistService(CatalogMapper catalog, WaitlistMapper queue, ReservationMapper reservations,
                           WaitlistCoordinator coordinator, Clock clock) {
        this.catalog=catalog; this.queue=queue; this.reservations=reservations; this.coordinator=coordinator; this.clock=clock;
    }
    public List<WaitlistView> mine(long userId) { return queue.mine(userId); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistView join(long userId, long slotId) {
        LockedSlot locked = lockSlot(slotId);
        LocalDateTime now = LocalDateTime.now(clock);
        requireFuture(locked.slot(), now);
        if (!"ACTIVE".equals(locked.device().status()) || !"OPEN".equals(locked.slot().status()))
            throw BusinessException.conflict("SLOT_CLOSED", "设备或时段未开放");
        ReservationRecord active = reservations.active(slotId);
        if (active != null && active.userId() == userId)
            throw BusinessException.conflict("ALREADY_RESERVED", "你已有此时段的预约或确认资格");
        if (queue.hasLive(slotId, userId) > 0)
            throw BusinessException.conflict("ALREADY_WAITING", "你已在此时段的候补队列中");
        if (active == null && queue.liveCount(slotId) == 0)
            throw BusinessException.conflict("SLOT_AVAILABLE", "时段尚有名额，请直接预约");
        queue.insert(userId, slotId, now);
        long id = queue.lastInsertId();
        // 同一设备的事务先后决定入队 ID，按 ID 先到先得，不接受客户端队号。
        coordinator.advanceLocked(locked.device(), locked.slot(), now);
        return queue.detail(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationView confirm(long userId, long id) {
        WaitlistEntry reference = requireOwner(id, userId);
        LockedSlot locked = lockSlot(reference.slotId());
        WaitlistEntry entry = requireOwner(id, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        if ("CONFIRMED".equals(entry.status())) return reservations.findDetail(entry.reservationId()).view(now);
        requireFuture(locked.slot(), now);
        if (!"OFFERED".equals(entry.status()) || !entry.expiresAt().isAfter(now))
            throw BusinessException.conflict("OFFER_UNAVAILABLE", "确认资格已失效，请刷新候补记录");
        if (!queue.enabled(userId) || !"ACTIVE".equals(locked.device().status()) || !"OPEN".equals(locked.slot().status()))
            throw BusinessException.conflict("SLOT_CLOSED", "账号或时段已不可用");
        // 期限边界严格：now < expiresAt。确认/超时都先锁同一设备和时段，只能一个成功。
        if (reservations.confirmHold(entry.reservationId(), now) != 1)
            throw BusinessException.conflict("OFFER_UNAVAILABLE", "确认资格已失效，请刷新候补记录");
        queue.finish(id, "CONFIRMED", now, null);
        coordinator.advanceLocked(locked.device(), locked.slot(), now);
        return reservations.findDetail(entry.reservationId()).view(now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistView withdraw(long userId, long id) {
        WaitlistEntry reference = requireOwner(id, userId);
        LockedSlot locked = lockSlot(reference.slotId());
        WaitlistEntry entry = requireOwner(id, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        if ("CANCELLED".equals(entry.status())) return queue.detail(id);
        requireFuture(locked.slot(), now);
        if (!"WAITING".equals(entry.status()) && !"OFFERED".equals(entry.status()))
            throw BusinessException.conflict("WAITLIST_FINISHED", "此候补已结束；已确认预约请到我的预约取消");
        if ("OFFERED".equals(entry.status()) && !entry.expiresAt().isAfter(now))
            throw BusinessException.conflict("OFFER_UNAVAILABLE", "确认资格已过期，请刷新候补记录");
        if (entry.reservationId() != null) reservations.cancel(entry.reservationId(), userId, now);
        queue.finish(id, "CANCELLED", now, "USER_WITHDREW");
        coordinator.advanceLocked(locked.device(), locked.slot(), now);
        return queue.detail(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processDue(long slotId) {
        LockedSlot locked = lockSlot(slotId);
        LocalDateTime now = LocalDateTime.now(clock);
        WaitlistTask task = queue.task(slotId);
        // 重复执行或第二个服务实例拿到旧扫描结果时，重读当前任务后直接跳过。
        if (task == null || !"PENDING".equals(task.status()) || task.nextRunAt().isAfter(now)) return;
        coordinator.advanceLocked(locked.device(), locked.slot(), now);
    }

    private WaitlistEntry requireOwner(long id, long userId) {
        if (id <= 0) throw BusinessException.invalid("候补 ID 必须为正整数");
        WaitlistEntry entry=queue.find(id);
        if (entry==null) throw new NotFoundException("候补不存在");
        if (entry.userId()!=userId) throw new BusinessException(403,"FORBIDDEN","只能操作自己的候补");
        return entry;
    }
    private LockedSlot lockSlot(long id) {
        if(id<=0) throw BusinessException.invalid("时段 ID 必须为正整数");
        SlotRow reference=catalog.findSlot(id);
        if(reference==null) throw new NotFoundException("时段不存在");
        Device device=catalog.lockDevice(reference.deviceId());
        SlotRow slot=catalog.lockSlot(id);
        if(device==null || slot==null) throw new NotFoundException("设备或时段已删除");
        return new LockedSlot(device,slot);
    }
    private void requireFuture(SlotRow slot, LocalDateTime now) {
        if(!slot.startAt().isAfter(now)) throw BusinessException.conflict("SLOT_STARTED","时段已开始，不能加入、确认或退出候补");
    }
    private record LockedSlot(Device device, SlotRow slot) {}
}
