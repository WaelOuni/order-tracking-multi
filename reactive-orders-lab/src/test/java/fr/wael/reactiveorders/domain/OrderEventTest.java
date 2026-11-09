package fr.wael.reactiveorders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventTest {
    @Test
    void eventCarriesSchemaAndAggregateVersionsWithoutCustomerData() {
        var order = new Order(UUID.randomUUID(), "CLIENT-DEMO", "MOUSE", "Souris", 2,
                2_990, OrderStatus.CREATED, Instant.parse("2026-11-09T10:00:00Z"), 0L);
        OrderEvent event = OrderEvent.of(order, "ORDER_CREATED", order.createdAt());
        assertThat(event.orderId()).isEqualTo(order.id());
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateVersion()).isZero();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.toString()).doesNotContain("CLIENT-DEMO");
    }

    @Test
    void unknownSchemaOrTypeIsAPoisonMessageRatherThanAnAcceptedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        assertThatThrownBy(() -> new OrderEvent(eventId, 2, orderId, "ORDER_CREATED", now, "MOUSE", 1, 0))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> new OrderEvent(eventId, 1, orderId, "UNKNOWN", now, "MOUSE", 1, 0))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void canonicalMillisecondPrecisionMakesADeliveryStableAfterBsonRoundTrip() {
        Instant withNanos = Instant.parse("2026-11-09T10:00:00.123456789Z");
        OrderEvent event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", withNanos, "MOUSE", 1, 0);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-09T10:00:00.123Z"));
        OrderEvent redelivery = new OrderEvent(event.eventId(), 1, event.orderId(), "ORDER_CREATED",
                Instant.ofEpochMilli(event.occurredAt().toEpochMilli()), "MOUSE", 1, 0);
        assertThat(redelivery).isEqualTo(event);
    }
}
