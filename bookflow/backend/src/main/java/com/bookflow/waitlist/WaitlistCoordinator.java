package com.bookflow.waitlist;

import com.bookflow.catalog.Device;
import com.bookflow.catalog.SlotRow;
import com.bookflow.reservation.ReservationMapper;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistCoordinator {
    private final WaitlistMapper queue;
    private final ReservationMapper reservations;
    private final long offerSeconds;
    public WaitlistCoordinator(WaitlistMapper queue, ReservationMapper reservations,
            @Value("${bookflow.waitlist.offer-seconds:900}") long offerSeconds) {
        if (offerSeconds < 1 || offerSeconds > 86400) throw new IllegalArgumentException("Offer duration must be 1..86400 seconds");
        this.queue = queue; this.reservations = reservations; this.offerSeconds = offerSeconds;
    }

    // 调用方必须已经按设备→时段顺序锁行；取消、确认、退出候补和扫描器共用此规则。
    // 状态迁移、释放/建立唯一占位、下一次任务期限在同一事务中完成。
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceLocked(Device device, SlotRow slot, LocalDateTime now) {
        boolean eligible = slot.startAt().isAfter(now) && "ACTIVE".equals(device.status()) && "OPEN".equals(slot.status());
        WaitlistEntry offer = queue.offered(slot.id());
        if (offer != null && (!eligible || !offer.expiresAt().isAfter(now))) {
            reservations.expireHold(offer.reservationId());
            queue.finish(offer.id(), "EXPIRED", now, eligible ? "CONFIRMATION_TIMEOUT" : "SLOT_UNAVAILABLE");
            offer = null;
        }
        if (!eligible) {
            queue.expireWaiting(slot.id(), now, "SLOT_UNAVAILABLE");
        } else if (offer == null && reservations.active(slot.id()) == null) {
            WaitlistEntry next;
            while ((next = queue.next(slot.id())) != null) {
                if (!queue.enabled(next.userId())) {
                    queue.finish(next.id(), "EXPIRED", now, "ACCOUNT_DISABLED");
                    continue;
                }
                LocalDateTime expires = now.plusSeconds(offerSeconds);
                if (expires.isAfter(slot.startAt())) expires = slot.startAt();
                reservations.hold(next.userId(), slot.id(), expires);
                long reservationId = reservations.lastInsertId();
                queue.offer(next.id(), reservationId, now, expires);
                offer = queue.offered(slot.id());
                break;
            }
        }
        LocalDateTime nextRun = offer != null ? offer.expiresAt()
                : queue.liveCount(slot.id()) > 0 ? slot.startAt() : null;
        queue.schedule(slot.id(), nextRun == null ? "DONE" : "PENDING", nextRun, now);
    }
}
