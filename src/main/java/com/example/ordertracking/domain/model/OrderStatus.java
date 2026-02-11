package com.example.ordertracking.domain.model;

public enum OrderStatus {
    CREATED,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return OrderStatusTransitions.canTransition(this, target);
    }
}
