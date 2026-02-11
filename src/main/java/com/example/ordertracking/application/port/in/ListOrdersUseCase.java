package com.example.ordertracking.application.port.in;

import com.example.ordertracking.application.port.out.OrderSearchQuery;
import com.example.ordertracking.domain.model.Order;

import java.util.List;

public interface ListOrdersUseCase {
    List<Order> listOrders(OrderSearchQuery query);
}
