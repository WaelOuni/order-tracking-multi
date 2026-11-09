package fr.wael.reactiveorders.domain;

import java.time.Instant;
import java.util.UUID;

/** Contrat d'événement versionné, sans donnée personnelle ni classe de persistance. */
public record OrderEvent(UUID eventId, int schemaVersion, UUID orderId, String type,
                         Instant occurredAt, String sku, int quantity, long aggregateVersion) {
    public OrderEvent {
        if (eventId == null || orderId == null || occurredAt == null || schemaVersion != 1
                || !("ORDER_CREATED".equals(type) || "ORDER_CANCELLED".equals(type))
                || sku == null || !sku.matches("[A-Z0-9_-]{1,40}") || quantity < 1 || quantity > 100
                || aggregateVersion < 0) throw new DomainRuleException("Événement incompatible ou invalide.");
        // Le contrat fixe sa précision pour qu'une redélivrance identique reste égale après stockage BSON.
        occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    public static OrderEvent of(Order order, String type, Instant now) {
        return new OrderEvent(UUID.randomUUID(), 1, order.id(), type, now,
                order.sku(), order.quantity(), order.version());
    }
}
