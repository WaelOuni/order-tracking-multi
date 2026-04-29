package com.example.ordertracking.domain.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Accessors(fluent = true)
@EqualsAndHashCode(of = "id")
@Builder(toBuilder = true)
public class Order {
    private final String id;
    private final String customerId;
    @Setter(AccessLevel.NONE)
    private OrderStatus status;
    private final Instant createdAt;
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private final List<TrackingEvent> history = new ArrayList<>();

    public static Order create(String id, String customerId) {
        Instant now = Instant.now();
        TrackingEvent created = new TrackingEvent(id, OrderStatus.CREATED.name(), now, "Order created");
        return Order.builder()
                .id(id)
                .customerId(customerId)
                .status(OrderStatus.CREATED)
                .createdAt(now)
                .updatedAt(now)
                .history(new ArrayList<>(List.of(created)))
                .build();
    }

    public static Order of(String id, String customerId, OrderStatus status, Instant createdAt, Instant updatedAt, List<TrackingEvent> history) {
        return Order.builder()
                .id(id)
                .customerId(customerId)
                .status(status)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .history(new ArrayList<>(history))
                .build();
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
}
