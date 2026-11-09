package fr.wael.reactiveorders.application.port;

import fr.wael.reactiveorders.domain.Order;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Le port exprime l'asynchronisme ; les détails Mongo restent dans l'adaptateur. */
public interface OrderStore {
    Mono<Order> findById(UUID id);
    Mono<Order> insert(Order order);
    Mono<Order> save(Order order);
    Flux<Order> list(int offset, int limit);
}
