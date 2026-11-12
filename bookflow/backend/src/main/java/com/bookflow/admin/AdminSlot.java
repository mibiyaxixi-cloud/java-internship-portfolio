package com.bookflow.admin;

import java.time.LocalDateTime;

public record AdminSlot(long id, long deviceId, LocalDateTime startAt, LocalDateTime endAt,
                        String status, boolean demo, boolean hasHistory, boolean occupied) {}
