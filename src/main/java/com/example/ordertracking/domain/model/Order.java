package com.example.ordertracking.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Order {
    private final String id;
    private final String customerId;
    private OrderStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<TrackingEvent> history;

    public Order(String id, String customerId, OrderStatus status, Instant createdAt, Instant updatedAt, List<TrackingEvent> history) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.history = new ArrayList<>(history);
    }

    public static Order create(String id, String customerId) {
        Instant now = Instant.now();
        TrackingEvent created = new TrackingEvent(id, OrderStatus.CREATED.name(), now, "Order created");
        return new Order(id, customerId, OrderStatus.CREATED, now, now, List.of(created));
    }

    public void transitionTo(OrderStatus target, Instant at, String note) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid transition from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = at;
        this.history.add(new TrackingEvent(id, target.name(), at, note));
    }

    public String id() { return id; }

    public String customerId() { return customerId; }

    public OrderStatus status() { return status; }

    public Instant createdAt() { return createdAt; }

    public Instant updatedAt() { return updatedAt; }

    public List<TrackingEvent> history() { return List.copyOf(history); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order order)) return false;
        return Objects.equals(id, order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
