package com.example.ordertracking.application.port.in;

import com.example.ordertracking.domain.model.Order;

public interface RegisterOrderUseCase {
    Order register(String orderId, String customerId);
}
