package com.bookflow.admin;

import com.bookflow.catalog.*;
import com.bookflow.common.BusinessException;
import com.bookflow.common.NotFoundException;
import com.bookflow.reservation.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminService {
    private final CatalogMapper catalog;
    private final AdminMapper admin;
    private final ReservationMapper reservations;
    private final Clock clock;

    public AdminService(CatalogMapper catalog, AdminMapper admin, ReservationMapper reservations, Clock clock) {
        this.catalog = catalog;
        this.admin = admin;
        this.reservations = reservations;
        this.clock = clock;
    }

    public List<ReservationView> reservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        return reservations.findAll().stream().map(row -> row.view(now)).toList();
    }

    public List<AdminSlot> slots(long deviceId, LocalDate date) {
        if (deviceId <= 0) throw BusinessException.invalid("设备 ID 必须为正整数");
        if (catalog.findDevice(deviceId) == null) throw new NotFoundException("设备不存在");
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        if (day.getYear() < 1000 || day.getYear() > 9998) throw BusinessException.invalid("日期超出支持范围");
        return admin.slots(deviceId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Device createDevice(DeviceInput input) {
        admin.insertDevice(input);
        return catalog.findDevice(admin.lastInsertId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Device updateDevice(long id, DeviceInput input) {
        Device current = lockDevice(id);
        if (!current.code().equals(input.code())) throw BusinessException.invalid("设备编号创建后不可修改");
        if ("INACTIVE".equals(input.status()) && (admin.countUnfinishedReservations(id, LocalDateTime.now(clock)) > 0 || admin.countLiveWaitlist(id) > 0)) {
            throw BusinessException.conflict("DEVICE_HAS_RESERVATIONS", "设备存在未结束的预约、限时资格或候补，不能停用");
        }
        admin.updateDevice(id, input);
        return catalog.findDevice(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDevice(long id) {
        lockDevice(id);
        if (admin.countSlots(id) > 0) throw BusinessException.conflict("DEVICE_HAS_SLOTS", "设备仍有关联时段，请先处理时段；预约历史不能删除");
        admin.deleteDevice(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SlotRow createSlot(long deviceId, SlotInput input) {
        lockDevice(deviceId);
        validateSlot(input);
        rejectOverlap(deviceId, 0, input);
        admin.insertSlot(deviceId, input);
        return catalog.findSlot(admin.lastInsertId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SlotRow updateSlot(long id, SlotInput input) {
        SlotRow current = lockSlotAfterDevice(id);
        validateSlot(input);
        if (!current.startAt().isAfter(LocalDateTime.now(clock))) throw BusinessException.conflict("SLOT_STARTED", "已开始的时段不能修改");
        if (admin.countHistory(id) > 0 && (!current.startAt().equals(input.startAt()) || !current.endAt().equals(input.endAt()))) {
            throw BusinessException.conflict("SLOT_HAS_HISTORY", "时段已有预约或候补历史，不能修改起止时间");
        }
        if (current.occupied() && "CLOSED".equals(input.status())) {
            throw BusinessException.conflict("SLOT_OCCUPIED", "时段存在有效预约，不能关闭");
        }
        rejectOverlap(current.deviceId(), id, input);
        admin.updateSlot(id, input);
        return catalog.findSlot(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteSlot(long id) {
        lockSlotAfterDevice(id);
        if (admin.countHistory(id) > 0) throw BusinessException.conflict("SLOT_HAS_HISTORY", "时段已有预约或候补历史，不能删除");
        admin.deleteSlot(id);
    }

    private Device lockDevice(long id) {
        if (id <= 0) throw BusinessException.invalid("设备 ID 必须为正整数");
        Device device = catalog.lockDevice(id);
        if (device == null) throw new NotFoundException("设备不存在");
        return device;
    }

    private SlotRow lockSlotAfterDevice(long id) {
        if (id <= 0) throw BusinessException.invalid("时段 ID 必须为正整数");
        SlotRow reference = catalog.findSlot(id);
        if (reference == null) throw new NotFoundException("时段不存在");
        // 与预约服务相同的顺序，防止管理修改与预约之间出现检查后被改写的窗口。
        lockDevice(reference.deviceId());
        SlotRow current = catalog.lockSlot(id);
        if (current == null) throw new NotFoundException("时段已删除");
        return current;
    }

    private void rejectOverlap(long deviceId, long excludedId, SlotInput input) {
        // 两个相邻时段 end == start 可以共存；关闭的时段仍占据排班位置。
        if (admin.countOverlaps(deviceId, excludedId, input.startAt(), input.endAt()) > 0) {
            throw BusinessException.conflict("SLOT_OVERLAP", "与该设备已有时段重叠，请调整起止时间");
        }
    }

    private void validateSlot(SlotInput input) {
        if (input.startAt() == null || input.endAt() == null || input.status() == null
                || !Set.of("OPEN", "CLOSED").contains(input.status())) throw BusinessException.invalid("请填写起止时间和有效状态");
        if (input.startAt().getYear() < 1000 || input.endAt().getYear() > 9998
                || !input.endAt().isAfter(input.startAt())) throw BusinessException.invalid("结束时间必须晚于开始时间且年份在 1000～9998 之间");
        if (input.startAt().getSecond() != 0 || input.startAt().getNano() != 0
                || input.endAt().getSecond() != 0 || input.endAt().getNano() != 0) throw BusinessException.invalid("固定时段请按整分钟设置");
        if (!input.startAt().isAfter(LocalDateTime.now(clock))) throw BusinessException.conflict("SLOT_STARTED", "只能设置尚未开始的时段");
    }
}
