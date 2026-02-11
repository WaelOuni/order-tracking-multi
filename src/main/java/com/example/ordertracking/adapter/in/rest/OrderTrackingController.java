package com.example.ordertracking.adapter.in.rest;

import com.example.ordertracking.adapter.in.rest.dto.OrderResponse;
import com.example.ordertracking.adapter.in.rest.dto.RegisterOrderRequest;
import com.example.ordertracking.adapter.in.rest.dto.UpdateOrderStatusRequest;
import com.example.ordertracking.application.port.in.RegisterOrderUseCase;
import com.example.ordertracking.application.port.in.TrackOrderUseCase;
import com.example.ordertracking.application.port.in.UpdateOrderStatusUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderTrackingController {

    private final RegisterOrderUseCase registerOrderUseCase;
    private final TrackOrderUseCase trackOrderUseCase;
    private final UpdateOrderStatusUseCase updateOrderStatusUseCase;
    private final OrderRestMapper mapper;

    public OrderTrackingController(RegisterOrderUseCase registerOrderUseCase,
                                   TrackOrderUseCase trackOrderUseCase,
                                   UpdateOrderStatusUseCase updateOrderStatusUseCase,
                                   OrderRestMapper mapper) {
        this.registerOrderUseCase = registerOrderUseCase;
        this.trackOrderUseCase = trackOrderUseCase;
        this.updateOrderStatusUseCase = updateOrderStatusUseCase;
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

    @PutMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable String id, @Valid @RequestBody UpdateOrderStatusRequest request) {
        return mapper.toResponse(updateOrderStatusUseCase.updateStatus(id, request.status(), request.note()));
    }
}
