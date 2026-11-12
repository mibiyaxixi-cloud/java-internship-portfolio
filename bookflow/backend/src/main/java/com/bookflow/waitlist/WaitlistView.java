package com.bookflow.waitlist;
import java.time.LocalDateTime;
public record WaitlistView(long id, long userId, String username, String displayName, long slotId,
    String deviceName, String deviceCode, LocalDateTime startAt, LocalDateTime endAt,
    String status, LocalDateTime queuedAt, LocalDateTime expiresAt, Long reservationId, String reason,
    int position) {}
