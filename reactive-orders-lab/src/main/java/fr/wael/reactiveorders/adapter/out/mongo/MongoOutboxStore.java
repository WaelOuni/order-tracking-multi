package fr.wael.reactiveorders.adapter.out.mongo;

import fr.wael.reactiveorders.application.ClaimedEvent;
import fr.wael.reactiveorders.application.Errors;
import fr.wael.reactiveorders.application.port.OutboxStore;
import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MongoOutboxStore implements OutboxStore {
    private final ReactiveMongoTemplate mongo;
    public MongoOutboxStore(ReactiveMongoTemplate mongo) { this.mongo = mongo; }
    @Override public Mono<Void> append(OrderEvent event) {
        return mongo.insert(new OutboxDocument(event.eventId().toString(), StoredEvent.from(event), "PENDING", 0,
                event.occurredAt(), event.occurredAt(), null, null, null, null)).then();
    }
    @Override public Mono<ClaimedEvent> claim(Instant now, Instant leaseUntil, String token) {
        Criteria ready = new Criteria().orOperator(
                Criteria.where("state").is("PENDING").and("nextAttemptAt").lte(now),
                Criteria.where("state").is("PROCESSING").and("leaseUntil").lte(now));
        Query query = Query.query(ready).with(Sort.by("createdAt", "_id"));
        Update update = new Update().set("state", "PROCESSING").set("leaseToken", token)
                .set("leaseUntil", leaseUntil).inc("attempts", 1);
        // findAndModify est atomique : deux instances ne réclament pas le même lease actif.
        return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), OutboxDocument.class)
                .map(doc -> new ClaimedEvent(doc.event().toDomain(), doc.leaseToken(), doc.attempts()));
    }
    @Override public Mono<Void> markPublished(UUID id, String token, Instant now) {
        // Le token empêche un ancien worker de modifier le travail repris par un autre après expiration.
        return mongo.updateFirst(owned(id, token), new Update().set("state", "SENT")
                        .set("expiresAt", now.plus(7, ChronoUnit.DAYS)).unset("leaseUntil").unset("leaseToken"), OutboxDocument.class)
                .flatMap(result -> result.getModifiedCount() == 1 ? Mono.<Void>empty() : Mono.error(new Errors.LostLease()));
    }
    @Override public Mono<Void> reschedule(ClaimedEvent claimed, Instant nextAttempt, boolean exhausted, String reason) {
        // FAILED reste inspectable : la TTL ne supprime que les événements réellement publiés.
        Update update = new Update().set("state", exhausted ? "FAILED" : "PENDING")
                .set("nextAttemptAt", nextAttempt).set("lastError", reason).unset("leaseToken").unset("leaseUntil");
        return mongo.updateFirst(owned(claimed.event().eventId(), claimed.token()), update, OutboxDocument.class).then();
    }
    private Query owned(UUID id, String token) {
        return Query.query(Criteria.where("_id").is(id.toString()).and("leaseToken").is(token).and("state").is("PROCESSING"));
    }
}
