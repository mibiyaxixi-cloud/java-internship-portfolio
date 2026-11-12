package com.bookflow.reservation;

public record ReservationRecord(long id, long userId, long slotId, String status) {}
