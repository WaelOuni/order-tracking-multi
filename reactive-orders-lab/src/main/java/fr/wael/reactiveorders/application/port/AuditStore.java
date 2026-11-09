package fr.wael.reactiveorders.application.port;

import fr.wael.reactiveorders.application.AuditEntry;
import fr.wael.reactiveorders.domain.OrderEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AuditStore {
    Mono<Void> appendIdempotently(OrderEvent event);
    Flux<AuditEntry> recent(int limit);
}
