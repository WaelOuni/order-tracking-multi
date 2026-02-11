package com.example.ordertracking.adapter.out.mongo;

import com.example.ordertracking.application.port.out.LoadOrderPort;
import com.example.ordertracking.application.port.out.LoadStaleOrdersPort;
import com.example.ordertracking.application.port.out.OrderSearchQuery;
import com.example.ordertracking.application.port.out.SaveOrderPort;
import com.example.ordertracking.application.port.out.SearchOrdersPort;
import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.domain.model.OrderStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class OrderMongoAdapter implements LoadOrderPort, SaveOrderPort, LoadStaleOrdersPort, SearchOrdersPort {

    private final SpringDataOrderRepository repository;
    private final OrderDocumentMapper mapper;
    private final MongoTemplate mongoTemplate;

    public OrderMongoAdapter(SpringDataOrderRepository repository, OrderDocumentMapper mapper, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
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

    @Override
    public List<Order> findAll(OrderSearchQuery query) {
        Query mongoQuery = new Query();
        if (query.orderIdContains() != null && !query.orderIdContains().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("_id").regex(containsIgnoreCase(query.orderIdContains())));
        }
        if (query.customerIdContains() != null && !query.customerIdContains().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("customerId").regex(containsIgnoreCase(query.customerIdContains())));
        }
        if (query.status() != null && !query.status().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("status").is(query.status()));
        }
        if (query.updatedFrom() != null || query.updatedTo() != null) {
            Criteria range = Criteria.where("updatedAt");
            if (query.updatedFrom() != null) {
                range = range.gte(query.updatedFrom());
            }
            if (query.updatedTo() != null) {
                range = range.lte(query.updatedTo());
            }
            mongoQuery.addCriteria(range);
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(query.sortDir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortField = "createdAt".equalsIgnoreCase(query.sortBy()) ? "createdAt" : "updatedAt";
        mongoQuery.with(Sort.by(direction, sortField));
        mongoQuery.skip((long) query.page() * query.size());
        mongoQuery.limit(query.size());

        return mongoTemplate.find(mongoQuery, OrderDocument.class)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private String containsIgnoreCase(String value) {
        return "(?i).*" + java.util.regex.Pattern.quote(value) + ".*";
    }
}
