package com.example.ordertracking.bdd;

import com.example.ordertracking.application.port.out.LoadOrderPort;
import com.example.ordertracking.application.port.out.LoadStaleOrdersPort;
import com.example.ordertracking.application.port.out.OrderSearchQuery;
import com.example.ordertracking.application.port.out.PublishOrderEventPort;
import com.example.ordertracking.application.port.out.SaveOrderPort;
import com.example.ordertracking.application.port.out.SearchOrdersPort;
import com.example.ordertracking.adapter.out.mongo.SpringDataOrderRepository;
import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class InMemoryOrderTrackingTestConfig {

    @Bean
    @Primary
    InMemoryOrderStore inMemoryOrderStore() {
        return new InMemoryOrderStore();
    }

    @Bean
    @Primary
    RecordingOrderEventPublisher recordingOrderEventPublisher() {
        return new RecordingOrderEventPublisher();
    }

    @Bean
    SpringDataOrderRepository springDataOrderRepository() {
        return mock(SpringDataOrderRepository.class);
    }

    @Bean
    MongoTemplate mongoTemplate() {
        return mock(MongoTemplate.class);
    }

    public static class InMemoryOrderStore implements LoadOrderPort, SaveOrderPort, LoadStaleOrdersPort, SearchOrdersPort {
        private final Map<String, Order> orders = new LinkedHashMap<>();

        void clear() {
            orders.clear();
        }

        @Override
        public Optional<Order> findById(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public Order save(Order order) {
            orders.put(order.id(), order);
            return order;
        }

        @Override
        public List<Order> findShippedBefore(Instant before) {
            return orders.values().stream()
                    .filter(order -> order.status() == OrderStatus.SHIPPED)
                    .filter(order -> order.updatedAt().isBefore(before))
                    .toList();
        }

        @Override
        public List<Order> findAll(OrderSearchQuery query) {
            List<Order> filtered = orders.values().stream()
                    .filter(order -> contains(order.id(), query.orderIdContains()))
                    .filter(order -> contains(order.customerId(), query.customerIdContains()))
                    .filter(order -> query.status() == null || order.status().name().equals(query.status()))
                    .filter(order -> query.updatedFrom() == null || !order.updatedAt().isBefore(query.updatedFrom()))
                    .filter(order -> query.updatedTo() == null || !order.updatedAt().isAfter(query.updatedTo()))
                    .sorted(sort(query))
                    .toList();

            int from = Math.min(query.page() * query.size(), filtered.size());
            int to = Math.min(from + query.size(), filtered.size());
            return filtered.subList(from, to);
        }

        private boolean contains(String value, String expected) {
            return expected == null || expected.isBlank() || value.contains(expected);
        }

        private Comparator<Order> sort(OrderSearchQuery query) {
            Comparator<Order> comparator = "createdAt".equals(query.sortBy())
                    ? Comparator.comparing(Order::createdAt)
                    : Comparator.comparing(Order::updatedAt);

            return "asc".equalsIgnoreCase(query.sortDir()) ? comparator : comparator.reversed();
        }
    }

    public static class RecordingOrderEventPublisher implements PublishOrderEventPort {
        private final List<Order> publishedOrders = new ArrayList<>();

        void clear() {
            publishedOrders.clear();
        }

        int publishedCount() {
            return publishedOrders.size();
        }

        @Override
        public void publishStatusChanged(Order order) {
            publishedOrders.add(order);
        }
    }
}
