package com.example.ordertracking.adapter.out.mongo;

import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import com.example.ordertracking.domain.model.TrackingEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.stream.Collectors;

@Component
public class OrderDocumentMapper {

    public Order toDomain(OrderDocument document) {
        return Order.of(
                document.getId(),
                document.getCustomerId(),
                OrderStatus.valueOf(document.getStatus()),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                new ArrayList<>(document.getHistory().stream()
                        .map(event -> new TrackingEvent(event.getOrderId(), event.getStatus(), event.getOccurredAt(), event.getNote()))
                        .toList())
        );
    }

    public OrderDocument toDocument(Order order) {
        return OrderDocument.builder()
                .id(order.id())
                .customerId(order.customerId())
                .status(order.status().name())
                .createdAt(order.createdAt())
                .updatedAt(order.updatedAt())
                .history(order.history().stream().map(event -> TrackingEventDocument.builder()
                        .orderId(event.orderId())
                        .status(event.status())
                        .occurredAt(event.occurredAt())
                        .note(event.note())
                        .build()).collect(Collectors.toList()))
                .build();
    }
}
