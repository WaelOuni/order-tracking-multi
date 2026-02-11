package com.example.ordertracking.application.port.out;

import com.example.ordertracking.domain.model.Order;

import java.time.Instant;
import java.util.List;

public interface LoadStaleOrdersPort {
    List<Order> findShippedBefore(Instant before);
}
