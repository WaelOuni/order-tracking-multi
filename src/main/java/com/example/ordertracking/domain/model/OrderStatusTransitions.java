package com.example.ordertracking.domain.model;

import java.util.EnumSet;
import java.util.Map;

public final class OrderStatusTransitions {

    private static final Map<OrderStatus, EnumSet<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.CREATED, EnumSet.of(OrderStatus.PACKED, OrderStatus.CANCELLED),
            OrderStatus.PACKED, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    private OrderStatusTransitions() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
    }
}
