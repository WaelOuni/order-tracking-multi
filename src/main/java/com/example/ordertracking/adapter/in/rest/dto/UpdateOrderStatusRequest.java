package com.example.ordertracking.adapter.in.rest.dto;

import com.example.ordertracking.domain.model.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(
        @NotNull OrderStatus status,
        @NotBlank String note
) {
}
