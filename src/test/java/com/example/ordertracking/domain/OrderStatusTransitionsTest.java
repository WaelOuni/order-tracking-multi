package com.example.ordertracking.domain;

import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStatusTransitionsTest {

    @Test
    void shouldAllowValidTransitions() {
        Order order = Order.create("o-1", "c-1");

        order.transitionTo(OrderStatus.PACKED, order.updatedAt().plusSeconds(60), "Packed");
        order.transitionTo(OrderStatus.SHIPPED, order.updatedAt().plusSeconds(60), "Shipped");

        assertEquals(OrderStatus.SHIPPED, order.status());
    }

    @Test
    void shouldRejectInvalidTransitions() {
        Order order = Order.create("o-2", "c-1");

        assertThrows(IllegalStateException.class,
                () -> order.transitionTo(OrderStatus.DELIVERED, order.updatedAt().plusSeconds(60), "Too early"));
    }
}
