package com.bookflow.catalog;

import java.time.LocalDateTime;

public record SlotRow(long id, long deviceId, LocalDateTime startAt, LocalDateTime endAt,
                      String status, boolean demo, boolean occupied) {}
