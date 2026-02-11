package com.example.ordertracking.application.port.out;

import com.example.ordertracking.domain.model.Order;

public interface PublishOrderEventPort {
    void publishStatusChanged(Order order);
}
