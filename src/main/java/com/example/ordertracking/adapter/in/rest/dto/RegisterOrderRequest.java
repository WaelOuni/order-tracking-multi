package com.example.ordertracking.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterOrderRequest(
        @NotBlank String orderId,
        @NotBlank String customerId
) {
}
