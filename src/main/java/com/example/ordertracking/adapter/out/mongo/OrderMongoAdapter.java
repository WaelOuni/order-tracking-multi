package com.example.ordertracking.adapter.out.mongo;

import com.example.ordertracking.application.port.out.LoadOrderPort;
import com.example.ordertracking.application.port.out.LoadStaleOrdersPort;
import com.example.ordertracking.application.port.out.SaveOrderPort;
import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class OrderMongoAdapter implements LoadOrderPort, SaveOrderPort, LoadStaleOrdersPort {

    private final SpringDataOrderRepository repository;
    private final OrderDocumentMapper mapper;

    public OrderMongoAdapter(SpringDataOrderRepository repository, OrderDocumentMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return repository.findById(orderId).map(mapper::toDomain);
    }

    @Override
    public Order save(Order order) {
        return mapper.toDomain(repository.save(mapper.toDocument(order)));
    }

    @Override
    public List<Order> findShippedBefore(Instant before) {
        return repository.findByStatusAndUpdatedAtBefore(OrderStatus.SHIPPED.name(), before)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
