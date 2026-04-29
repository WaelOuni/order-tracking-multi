package com.example.ordertracking.adapter.out.kafka;

import com.example.ordertracking.application.port.out.PublishOrderEventPort;
import com.example.ordertracking.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaOrderEventPublisher implements PublishOrderEventPort {

    private final KafkaTemplate<String, OrderStatusChangedEvent> kafkaTemplate;
    @Value("${app.kafka.order-status-topic:order.status.changed}")
    private String topic;

    @Override
    public void publishStatusChanged(Order order) {
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                order.id(),
                order.customerId(),
                order.status().name(),
                order.updatedAt()
        );
        kafkaTemplate.send(topic, order.id(), event);
    }
}
