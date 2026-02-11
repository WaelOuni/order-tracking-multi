package com.example.ordertracking.application.port.in;

import com.example.ordertracking.domain.model.Order;

public interface TrackOrderUseCase {
    Order getById(String orderId);
}
