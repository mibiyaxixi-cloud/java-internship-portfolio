package com.bookflow.waitlist;
import java.time.LocalDateTime;
public record WaitlistEntry(long id, long userId, long slotId, String status, LocalDateTime queuedAt,
    LocalDateTime offeredAt, LocalDateTime expiresAt, LocalDateTime completedAt, Long reservationId, String reason) {}
