package com.example.ordertracking.application.service;

import com.example.ordertracking.application.port.in.RegisterOrderUseCase;
import com.example.ordertracking.application.port.in.TrackOrderUseCase;
import com.example.ordertracking.application.port.in.UpdateOrderStatusUseCase;
import com.example.ordertracking.application.port.out.LoadOrderPort;
import com.example.ordertracking.application.port.out.LoadStaleOrdersPort;
import com.example.ordertracking.application.port.out.PublishOrderEventPort;
import com.example.ordertracking.application.port.out.SaveOrderPort;
import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Observed(name = "order.tracking.service")
public class OrderTrackingService implements RegisterOrderUseCase, TrackOrderUseCase, UpdateOrderStatusUseCase {

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final LoadStaleOrdersPort loadStaleOrdersPort;

    public OrderTrackingService(LoadOrderPort loadOrderPort,
                                SaveOrderPort saveOrderPort,
                                PublishOrderEventPort publishOrderEventPort,
                                LoadStaleOrdersPort loadStaleOrdersPort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.publishOrderEventPort = publishOrderEventPort;
        this.loadStaleOrdersPort = loadStaleOrdersPort;
    }

    @Override
    public Order register(String orderId, String customerId) {
        loadOrderPort.findById(orderId).ifPresent(existing -> {
            throw new IllegalStateException("Order already exists: " + orderId);
        });
        Order order = Order.create(orderId, customerId);
        Order saved = saveOrderPort.save(order);
        publishOrderEventPort.publishStatusChanged(saved);
        return saved;
    }

    @Override
    public Order getById(String orderId) {
        return loadOrderPort.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Override
    public Order updateStatus(String orderId, OrderStatus target, String note) {
        Order order = getById(orderId);
        order.transitionTo(target, Instant.now(), note);
        Order saved = saveOrderPort.save(order);
        publishOrderEventPort.publishStatusChanged(saved);
        return saved;
    }

    public int autoCompleteDeliveredForStaleShippedOrders() {
        Instant threshold = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Order> staleOrders = loadStaleOrdersPort.findShippedBefore(threshold);
        staleOrders.forEach(order -> {
            order.transitionTo(OrderStatus.DELIVERED, Instant.now(), "Auto-complete by batch job");
            Order saved = saveOrderPort.save(order);
            publishOrderEventPort.publishStatusChanged(saved);
        });
        return staleOrders.size();
    }
}
