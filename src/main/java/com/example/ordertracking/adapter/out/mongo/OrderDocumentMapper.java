package com.example.ordertracking.adapter.out.mongo;

import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import com.example.ordertracking.domain.model.TrackingEvent;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class OrderDocumentMapper {

    public Order toDomain(OrderDocument document) {
        return new Order(
                document.getId(),
                document.getCustomerId(),
                OrderStatus.valueOf(document.getStatus()),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getHistory().stream()
                        .map(event -> new TrackingEvent(event.getOrderId(), event.getStatus(), event.getOccurredAt(), event.getNote()))
                        .toList()
        );
    }

    public OrderDocument toDocument(Order order) {
        OrderDocument document = new OrderDocument();
        document.setId(order.id());
        document.setCustomerId(order.customerId());
        document.setStatus(order.status().name());
        document.setCreatedAt(order.createdAt());
        document.setUpdatedAt(order.updatedAt());
        document.setHistory(order.history().stream().map(event -> {
            TrackingEventDocument history = new TrackingEventDocument();
            history.setOrderId(event.orderId());
            history.setStatus(event.status());
            history.setOccurredAt(event.occurredAt());
            history.setNote(event.note());
            return history;
        }).collect(Collectors.toList()));
        return document;
    }
}
