package com.bookflow.waitlist;
import java.time.LocalDateTime;
public record WaitlistTask(long slotId, String status, LocalDateTime nextRunAt, int failureCount, String lastError) {}
