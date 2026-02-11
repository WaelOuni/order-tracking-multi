package com.example.ordertracking.adapter.in.rest.dto;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String id,
        String customerId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<TrackingEventResponse> history
) {
}
