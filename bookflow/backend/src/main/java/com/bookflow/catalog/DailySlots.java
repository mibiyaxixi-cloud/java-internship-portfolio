package com.bookflow.catalog;

import java.time.LocalDate;
import java.util.List;

public record DailySlots(long deviceId, LocalDate date, String timeZone, List<SlotView> slots) {}
