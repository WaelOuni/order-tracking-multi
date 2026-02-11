package com.example.ordertracking.domain.model;

import java.time.Instant;

public record TrackingEvent(
        String orderId,
        String status,
        Instant occurredAt,
        String note
) {
}
