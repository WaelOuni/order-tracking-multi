package com.example.ordertracking.adapter.out.kafka;

import java.time.Instant;

public record OrderStatusChangedEvent(
        String orderId,
        String customerId,
        String status,
        Instant updatedAt
) {
}
