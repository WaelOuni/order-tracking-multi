package com.example.ordertracking.adapter.out.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDocument {
    @Id
    private String id;
    private String customerId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<TrackingEventDocument> history = new ArrayList<>();
}
