package com.example.ordertracking.application.port.out;

import com.example.ordertracking.domain.model.Order;

import java.util.List;

public interface SearchOrdersPort {
    List<Order> findAll(OrderSearchQuery query);
}
