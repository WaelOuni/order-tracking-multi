package fr.wael.reactiveorders.application;

import fr.wael.reactiveorders.application.port.EventPublisher;
import fr.wael.reactiveorders.application.port.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Livraison au moins une fois. Le lease limite la concurrence, il ne supprime pas la fenêtre de crash. */
public final class OutboxRelay {
    private final OutboxStore store;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxStore store, EventPublisher publisher, Clock clock, int batchSize, int maxAttempts) {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 20)
            throw new IllegalArgumentException("Limites de relay invalides.");
        this.store = store; this.publisher = publisher; this.clock = clock;
        this.batchSize = batchSize; this.maxAttempts = maxAttempts;
    }

    public Mono<Integer> runOnce() {
        // Le @Scheduled réutilise le Publisher : defer doit créer de nouvelles dates à CHAQUE souscription.
        return Mono.defer(() -> Flux.range(0, batchSize)
                // concatMap : au plus un envoi applicatif en vol par relay, prélecture limitée à 1.
                .concatMap(index -> Mono.defer(() -> store.claim(clock.instant(),
                                clock.instant().plusSeconds(60), UUID.randomUUID().toString()))
                        .flatMap(this::deliver).defaultIfEmpty(-1), 1)
                // Le premier résultat vide termine le lot, au lieu d'effectuer vingt lectures inutiles.
                .takeWhile(result -> result >= 0)
                .reduce(0, Integer::sum));
    }

    private Mono<Integer> deliver(ClaimedEvent claimed) {
        // Des crashes répétés peuvent consommer les leases sans passer par onErrorResume.
        if (claimed.attempts() > maxAttempts) {
            return store.reschedule(claimed, clock.instant(), true, "LeaseRecoveryLimit").thenReturn(0);
        }
        return Mono.defer(() -> publisher.publish(claimed.event()))
                // Bornage de notre attente ; une annulation ne peut pas annuler un envoi déjà parti au broker.
                .timeout(Duration.ofSeconds(15))
                // On ne marque SENT qu'après l'ack Kafka. Un crash entre ces deux étapes produit un doublon.
                .then(Mono.defer(() -> store.markPublished(claimed.event().eventId(), claimed.token(), clock.instant())))
                .thenReturn(1)
                // L'erreur reste durablement visible dans l'outbox ; elle n'est pas transformée en succès de livraison.
                .onErrorResume(error -> store.reschedule(claimed,
                                clock.instant().plusSeconds(Math.min(60, 1L << Math.min(claimed.attempts(), 6))),
                                claimed.attempts() >= maxAttempts, error.getClass().getSimpleName())
                        .thenReturn(0));
    }
}
