package fr.wael.reactiveorders.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(@NotBlank @Size(max=64) String customerRef,
                                 @NotBlank @Pattern(regexp="[A-Z0-9_-]{1,40}") String sku,
                                 @Min(1) @Max(100) int quantity) {}
