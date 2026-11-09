package fr.wael.reactiveorders.adapter.out.mongo;

import fr.wael.reactiveorders.application.AuditEntry;
import fr.wael.reactiveorders.application.port.AuditStore;
import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MongoAuditStore implements AuditStore {
    private final ReactiveMongoTemplate mongo;
    private final Clock clock;
    public MongoAuditStore(ReactiveMongoTemplate mongo, Clock clock) { this.mongo = mongo; this.clock = clock; }
    @Override public Mono<Void> appendIdempotently(OrderEvent event) {
        // L'insert et l'index unique _id réalisent la déduplication, sans course exists-then-insert.
        return Mono.defer(() -> mongo.insert(new AuditDocument(event.eventId().toString(), StoredEvent.from(event), clock.instant())))
                .onErrorResume(DuplicateKeyException.class, error -> mongo.findById(event.eventId().toString(), AuditDocument.class)
                        .switchIfEmpty(Mono.error(error))
                        // Un même ID avec un contenu différent est une anomalie, pas un doublon bénin.
                        .flatMap(existing -> existing.event().equals(StoredEvent.from(event)) ? Mono.just(existing)
                                : Mono.error(new IllegalArgumentException("eventId réutilisé avec un contenu différent."))))
                .then(); // L'ack du listener attend la FIN de cette insertion/validation.
    }
    @Override public Flux<AuditEntry> recent(int limit) {
        Query query = new Query().with(Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.asc("_id"))).limit(Math.min(50, Math.max(1, limit)));
        return mongo.find(query, AuditDocument.class).map(doc -> new AuditEntry(UUID.fromString(doc.id()),
                UUID.fromString(doc.event().orderId()), doc.event().type(), doc.event().occurredAt(),
                doc.recordedAt(), doc.event().sku(), doc.event().quantity()));
    }
}
