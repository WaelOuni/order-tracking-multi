package com.example.ordertracking.application.port.out;

import com.example.ordertracking.domain.model.Order;

import java.util.Optional;

public interface LoadOrderPort {
    Optional<Order> findById(String orderId);
}
