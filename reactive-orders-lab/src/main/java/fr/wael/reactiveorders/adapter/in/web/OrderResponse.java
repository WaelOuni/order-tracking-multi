package fr.wael.reactiveorders.adapter.in.web;

import fr.wael.reactiveorders.domain.Order;
import java.time.Instant;
import java.util.UUID;

/** DTO explicite : modifier l'agrégat ne modifie pas accidentellement notre contrat HTTP. */
public record OrderResponse(UUID id, String customerRef, String sku, String productName, int quantity,
                            long unitPriceCents, long totalCents, String status, Instant createdAt, Long version) {
    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.customerRef(), order.sku(), order.productName(), order.quantity(),
                order.unitPriceCents(), order.totalCents(), order.status().name(), order.createdAt(), order.version());
    }
}
