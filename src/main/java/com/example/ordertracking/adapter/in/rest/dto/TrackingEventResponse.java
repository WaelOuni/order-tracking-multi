package com.example.ordertracking.adapter.in.rest.dto;

import java.time.Instant;

public record TrackingEventResponse(
        String status,
        Instant occurredAt,
        String note
) {
}
