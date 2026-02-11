package com.example.ordertracking.application.port.out;

import com.example.ordertracking.domain.model.Order;

public interface SaveOrderPort {
    Order save(Order order);
}
