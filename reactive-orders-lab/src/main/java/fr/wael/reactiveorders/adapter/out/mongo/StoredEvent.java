package fr.wael.reactiveorders.adapter.out.mongo;

import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Instant;
import java.util.UUID;

/** UUID en chaînes dans Mongo : le format BSON ne dépend pas d'un uuidRepresentation implicite. */
public record StoredEvent(String eventId, int schemaVersion, String orderId, String type,
                          Instant occurredAt, String sku, int quantity, long aggregateVersion) {
    static StoredEvent from(OrderEvent e) {
        return new StoredEvent(e.eventId().toString(), e.schemaVersion(), e.orderId().toString(), e.type(),
                e.occurredAt(), e.sku(), e.quantity(), e.aggregateVersion());
    }
    OrderEvent toDomain() {
        return new OrderEvent(UUID.fromString(eventId), schemaVersion, UUID.fromString(orderId), type,
                occurredAt, sku, quantity, aggregateVersion);
    }
}
