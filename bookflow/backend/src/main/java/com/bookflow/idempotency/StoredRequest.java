package com.bookflow.idempotency;
public record StoredRequest(String operation, long resourceId, Integer responseStatus, String responseJson) {}
