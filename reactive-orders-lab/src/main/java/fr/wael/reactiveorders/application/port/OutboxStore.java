package fr.wael.reactiveorders.application.port;

import fr.wael.reactiveorders.application.ClaimedEvent;
import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface OutboxStore {
    Mono<Void> append(OrderEvent event);
    Mono<ClaimedEvent> claim(Instant now, Instant leaseUntil, String token);
    Mono<Void> markPublished(UUID eventId, String token, Instant now);
    Mono<Void> reschedule(ClaimedEvent claimed, Instant nextAttempt, boolean exhausted, String reason);
}
