package com.example.ordertracking.application;

import com.example.ordertracking.application.port.out.LoadOrderPort;
import com.example.ordertracking.application.port.out.LoadStaleOrdersPort;
import com.example.ordertracking.application.port.out.PublishOrderEventPort;
import com.example.ordertracking.application.port.out.SaveOrderPort;
import com.example.ordertracking.application.port.out.SearchOrdersPort;
import com.example.ordertracking.application.service.OrderTrackingService;
import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTrackingServiceTest {

    @Mock
    private LoadOrderPort loadOrderPort;
    @Mock
    private SaveOrderPort saveOrderPort;
    @Mock
    private PublishOrderEventPort publishOrderEventPort;
    @Mock
    private LoadStaleOrdersPort loadStaleOrdersPort;
    @Mock
    private SearchOrdersPort searchOrdersPort;

    private OrderTrackingService service;

    @BeforeEach
    void setUp() {
        service = new OrderTrackingService(
                loadOrderPort,
                saveOrderPort,
                publishOrderEventPort,
                loadStaleOrdersPort,
                searchOrdersPort
        );
    }


    @Test
    void shouldRejectDuplicateOrderRegistration() {
        Order existing = Order.create("o-dup", "c-1");
        when(loadOrderPort.findById("o-dup")).thenReturn(Optional.of(existing));

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.register("o-dup", "c-1")
        );

        assertEquals("Order already exists: o-dup", ex.getMessage());
    }

    @Test
    void shouldUpdateStatusAndPublish() {
        Order order = Order.create("o-123", "c-9");
        when(loadOrderPort.findById("o-123")).thenReturn(Optional.of(order));
        when(saveOrderPort.save(ArgumentMatchers.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order updated = service.updateStatus("o-123", OrderStatus.PACKED, "warehouse packed");

        assertEquals(OrderStatus.PACKED, updated.status());
        verify(publishOrderEventPort, times(1)).publishStatusChanged(updated);
    }

    @Test
    void batchShouldCompleteStaleShippedOrders() {
        Order stale = Order.create("o-999", "c-2");
        stale.transitionTo(OrderStatus.PACKED, Instant.now().minusSeconds(10000), "Packed");
        stale.transitionTo(OrderStatus.SHIPPED, Instant.now().minusSeconds(9000), "Shipped");

        when(loadStaleOrdersPort.findShippedBefore(ArgumentMatchers.any(Instant.class))).thenReturn(List.of(stale));
        when(saveOrderPort.save(ArgumentMatchers.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int processed = service.autoCompleteDeliveredForStaleShippedOrders();

        assertEquals(1, processed);
        assertEquals(OrderStatus.DELIVERED, stale.status());
        verify(publishOrderEventPort).publishStatusChanged(stale);
    }
}
