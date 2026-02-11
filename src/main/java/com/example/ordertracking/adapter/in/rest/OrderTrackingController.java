package com.example.ordertracking.adapter.in.rest;

import com.example.ordertracking.adapter.in.rest.dto.OrderResponse;
import com.example.ordertracking.adapter.in.rest.dto.RegisterOrderRequest;
import com.example.ordertracking.adapter.in.rest.dto.UpdateOrderStatusRequest;
import com.example.ordertracking.application.port.in.ListOrdersUseCase;
import com.example.ordertracking.application.port.in.RegisterOrderUseCase;
import com.example.ordertracking.application.port.in.TrackOrderUseCase;
import com.example.ordertracking.application.port.in.UpdateOrderStatusUseCase;
import com.example.ordertracking.application.port.out.OrderSearchQuery;
import com.example.ordertracking.domain.model.OrderStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderTrackingController {

    private final RegisterOrderUseCase registerOrderUseCase;
    private final TrackOrderUseCase trackOrderUseCase;
    private final UpdateOrderStatusUseCase updateOrderStatusUseCase;
    private final ListOrdersUseCase listOrdersUseCase;
    private final OrderRestMapper mapper;

    public OrderTrackingController(RegisterOrderUseCase registerOrderUseCase,
                                   TrackOrderUseCase trackOrderUseCase,
                                   UpdateOrderStatusUseCase updateOrderStatusUseCase,
                                   ListOrdersUseCase listOrdersUseCase,
                                   OrderRestMapper mapper) {
        this.registerOrderUseCase = registerOrderUseCase;
        this.trackOrderUseCase = trackOrderUseCase;
        this.updateOrderStatusUseCase = updateOrderStatusUseCase;
        this.listOrdersUseCase = listOrdersUseCase;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse register(@Valid @RequestBody RegisterOrderRequest request) {
        return mapper.toResponse(registerOrderUseCase.register(request.orderId(), request.customerId()));
    }

    @GetMapping("/{id}")
    public OrderResponse track(@PathVariable String id) {
        return mapper.toResponse(trackOrderUseCase.getById(id));
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) String orderId,
                                    @RequestParam(required = false) String customerId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String updatedFrom,
                                    @RequestParam(required = false) String updatedTo,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    @RequestParam(defaultValue = "updatedAt") String sortBy,
                                    @RequestParam(defaultValue = "desc") String sortDir) {
        String normalizedStatus = normalizeStatus(status);
        Instant from = parseInstant(updatedFrom);
        Instant to = parseInstant(updatedTo);
        OrderSearchQuery query = new OrderSearchQuery(orderId, customerId, normalizedStatus, from, to, page, size, sortBy, sortDir);
        return listOrdersUseCase.listOrders(query).stream().map(mapper::toResponse).toList();
    }

    @PutMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable String id, @Valid @RequestBody UpdateOrderStatusRequest request) {
        return mapper.toResponse(updateOrderStatusUseCase.updateStatus(id, request.status(), request.note()));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        OrderStatus.valueOf(normalized);
        return normalized;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
