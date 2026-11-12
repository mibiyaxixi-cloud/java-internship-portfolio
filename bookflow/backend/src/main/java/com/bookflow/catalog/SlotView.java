package com.bookflow.catalog;

import java.time.LocalDateTime;

public record SlotView(long id, LocalDateTime startAt, LocalDateTime endAt,
                       String availability, boolean demo) {}
