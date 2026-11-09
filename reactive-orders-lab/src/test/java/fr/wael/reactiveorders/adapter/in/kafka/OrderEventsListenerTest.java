package fr.wael.reactiveorders.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import fr.wael.reactiveorders.application.port.AuditStore;
import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import tools.jackson.databind.json.JsonMapper;

class OrderEventsListenerTest {
    @Test
    void returnedMonoWaitsUntilAuditPersistenceCompletes() {
        var audit = mock(AuditStore.class);
        var mapper = JsonMapper.builder().build();
        var event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", Instant.now(), "KEYBOARD", 2, 0);
        TestPublisher<Void> persistence = TestPublisher.create();
        when(audit.appendIdempotently(event)).thenReturn(persistence.mono());
        var listener = new OrderEventsListener(mapper, audit);

        var processing = listener.onMessage(mapper.writeValueAsString(event));
        verifyNoInteractions(audit);
        StepVerifier.create(processing)
                .then(() -> verify(audit).appendIdempotently(event))
                .then(persistence::complete)
                .expectComplete().verify(Duration.ofSeconds(5));
    }

    @Test
    void malformedJsonBecomesAnErrorSignalInsteadOfThrowingBeforeSubscription() {
        var audit = mock(AuditStore.class);
        var listener = new OrderEventsListener(JsonMapper.builder().build(), audit);
        // La création du Mono elle-même ne doit pas lancer l’exception de parsing.
        var processing = listener.onMessage("{broken-json");
        StepVerifier.create(processing).expectError().verify(Duration.ofSeconds(5));
        verifyNoInteractions(audit);
    }

    @Test
    void databaseFailureRemainsAnErrorForTheKafkaContainer() {
        var audit = mock(AuditStore.class);
        var mapper = JsonMapper.builder().build();
        var event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", Instant.now(), "KEYBOARD", 2, 0);
        var failure = new IllegalStateException("Mongo inaccessible");
        when(audit.appendIdempotently(event)).thenReturn(Mono.error(failure));

        StepVerifier.create(new OrderEventsListener(mapper, audit).onMessage(mapper.writeValueAsString(event)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify(Duration.ofSeconds(5));
    }
}
