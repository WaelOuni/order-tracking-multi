package com.example.ordertracking.application.port.in;

import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;

public interface UpdateOrderStatusUseCase {
    Order updateStatus(String orderId, OrderStatus target, String note);
}
