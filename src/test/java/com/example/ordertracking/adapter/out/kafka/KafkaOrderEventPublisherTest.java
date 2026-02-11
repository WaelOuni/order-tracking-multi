package com.example.ordertracking.adapter.out.kafka;

import com.example.ordertracking.domain.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaOrderEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, OrderStatusChangedEvent> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void shouldPublishStatusEvent() {
        KafkaOrderEventPublisher publisher = new KafkaOrderEventPublisher(kafkaTemplate, "order.status.changed");
        Order order = Order.create("o-kafka", "c-kafka");

        publisher.publishStatusChanged(order);

        verify(kafkaTemplate).send(eq("order.status.changed"), eq("o-kafka"), org.mockito.ArgumentMatchers.any(OrderStatusChangedEvent.class));
    }
}
