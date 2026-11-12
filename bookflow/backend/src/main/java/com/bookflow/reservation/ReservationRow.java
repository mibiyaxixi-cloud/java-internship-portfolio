package com.bookflow.reservation;

import java.time.LocalDateTime;

public record ReservationRow(long id, long userId, String username, String displayName,
                             long deviceId, String deviceName, String deviceCode, String location,
                             long slotId, LocalDateTime startAt, LocalDateTime endAt, String status,
                             LocalDateTime createdAt, LocalDateTime cancelledAt, LocalDateTime offerExpiresAt) {
    public ReservationView view(LocalDateTime now) {
        return new ReservationView(this, "CONFIRMED".equals(status) && startAt.isAfter(now));
    }
}
