package com.example.ordertracking.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface SpringDataOrderRepository extends MongoRepository<OrderDocument, String> {
    List<OrderDocument> findByStatusAndUpdatedAtBefore(String status, Instant updatedAt);
}
