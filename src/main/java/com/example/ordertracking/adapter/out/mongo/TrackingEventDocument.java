package com.example.ordertracking.adapter.out.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingEventDocument {
    private String orderId;
    private String status;
    private Instant occurredAt;
    private String note;
}
