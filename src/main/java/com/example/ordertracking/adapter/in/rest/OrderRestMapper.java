package com.example.ordertracking.adapter.in.rest;

import com.example.ordertracking.adapter.in.rest.dto.OrderResponse;
import com.example.ordertracking.adapter.in.rest.dto.TrackingEventResponse;
import com.example.ordertracking.domain.model.Order;
import org.springframework.stereotype.Component;

@Component
public class OrderRestMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.id(),
                order.customerId(),
                order.status().name(),
                order.createdAt(),
                order.updatedAt(),
                order.history().stream().map(e -> new TrackingEventResponse(e.status(), e.occurredAt(), e.note())).toList()
        );
    }
}
