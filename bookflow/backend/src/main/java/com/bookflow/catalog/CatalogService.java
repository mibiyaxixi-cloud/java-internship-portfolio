package com.bookflow.catalog;

import com.bookflow.common.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final CatalogMapper mapper;
    private final Clock clock;

    public CatalogService(CatalogMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<Device> listDevices() {
        return mapper.findDevices();
    }

    // 在 MySQL 默认 REPEATABLE READ 下，两次查询使用同一只读事务快照。
    // 这只是展示快照，不是预约承诺；M2 写入必须重新校验并由数据库约束兜底。
    @Transactional(readOnly = true)
    public DailySlots listSlots(long deviceId, LocalDate requestedDate) {
        if (deviceId <= 0) throw new IllegalArgumentException("Invalid device id");
        LocalDate date = requestedDate == null ? LocalDate.now(clock) : requestedDate;
        if (date.getYear() < 1000 || date.getYear() > 9998) {
            throw new IllegalArgumentException("Date outside MySQL range");
        }
        Device device = mapper.findDevice(deviceId);
        if (device == null) throw new NotFoundException("设备不存在");
        LocalDateTime now = LocalDateTime.now(clock);
        List<SlotView> slots = mapper.findSlots(deviceId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream()
                .map(slot -> new SlotView(slot.id(), slot.startAt(), slot.endAt(), availability(device, slot, now), slot.demo()))
                .toList();
        return new DailySlots(deviceId, date, "Asia/Shanghai", slots);
    }

    static String availability(Device device, SlotRow slot, LocalDateTime now) {
        if (!slot.endAt().isAfter(now)) return "ENDED";
        if (!slot.startAt().isAfter(now)) return "STARTED";
        if (!"ACTIVE".equals(device.status()) || !"OPEN".equals(slot.status())) return "CLOSED";
        return slot.occupied() ? "OCCUPIED" : "AVAILABLE";
    }
}
