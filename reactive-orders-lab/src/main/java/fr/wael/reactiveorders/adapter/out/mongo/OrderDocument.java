package fr.wael.reactiveorders.adapter.out.mongo;

import fr.wael.reactiveorders.domain.Order;
import fr.wael.reactiveorders.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("orders")
@CompoundIndex(name = "orders_recent", def = "{'createdAt': -1, '_id': 1}")
public record OrderDocument(@Id String id, String customerRef, String sku, String productName,
                            int quantity, long unitPriceCents, OrderStatus status, Instant createdAt,
                            @Version Long version) {
    static OrderDocument from(Order order) {
        return new OrderDocument(order.id().toString(), order.customerRef(), order.sku(), order.productName(),
                order.quantity(), order.unitPriceCents(), order.status(), order.createdAt(), order.version());
    }
    Order toDomain() {
        return new Order(UUID.fromString(id), customerRef, sku, productName, quantity, unitPriceCents, status, createdAt, version);
    }
}
