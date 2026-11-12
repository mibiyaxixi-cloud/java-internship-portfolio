package com.bookflow.admin;

import java.time.LocalDateTime;

public record SlotInput(LocalDateTime startAt, LocalDateTime endAt, String status) {}
