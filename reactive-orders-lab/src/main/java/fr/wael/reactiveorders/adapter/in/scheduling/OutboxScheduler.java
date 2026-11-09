package fr.wael.reactiveorders.adapter.in.scheduling;

import fr.wael.reactiveorders.application.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxRelay relay;
    private final MeterRegistry metrics;
    public OutboxScheduler(OutboxRelay relay, MeterRegistry metrics) { this.relay = relay; this.metrics = metrics; }
    @Scheduled(fixedDelayString = "${app.outbox.poll-delay:1000}")
    public Mono<Void> publish() {
        return relay.runOnce()
                .doOnNext(count -> metrics.counter("orders.outbox.published").increment(count))
                .doOnError(error -> {
                    metrics.counter("orders.outbox.poll.errors").increment();
                    // Pas de payload/client dans les logs ; l'état FAILED reste consultable dans Mongo.
                    LOG.warn("Outbox poll failed: {}", error.getClass().getSimpleName());
                }).then();
    }
}
