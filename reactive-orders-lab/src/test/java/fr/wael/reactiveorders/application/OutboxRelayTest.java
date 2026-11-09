package fr.wael.reactiveorders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.wael.reactiveorders.application.port.EventPublisher;
import fr.wael.reactiveorders.application.port.OutboxStore;
import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

class OutboxRelayTest {
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private OutboxStore outbox;
    private EventPublisher publisher;
    private OrderEvent event;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxStore.class);
        publisher = mock(EventPublisher.class);
        event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", NOW, "KEYBOARD", 2, 0);
    }

    private ClaimedEvent claim(int attempts) { return new ClaimedEvent(event, "owner-token", attempts); }
    private OutboxRelay relay(int batchSize) { return new OutboxRelay(outbox, publisher, CLOCK, batchSize, 3); }

    @Test
    @DisplayName("Le relay ne marque SENT qu’après confirmation du broker")
    void markPublishedWaitsForTheAcknowledgementAndThePublisherIsLazy() {
        TestPublisher<Void> acknowledgement = TestPublisher.create();
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claim(1)));
        when(publisher.publish(event)).thenReturn(acknowledgement.mono());
        when(outbox.markPublished(eq(event.eventId()), eq("owner-token"), eq(NOW))).thenReturn(Mono.empty());

        Mono<Integer> operation = relay(1).runOnce();
        verifyNoInteractions(outbox, publisher);
        StepVerifier.create(operation)
                .then(() -> verify(outbox, never()).markPublished(any(), anyString(), any()))
                .then(acknowledgement::complete)
                .expectNext(1)
                .verifyComplete();
        verify(outbox).markPublished(event.eventId(), "owner-token", NOW);
        verify(outbox, never()).reschedule(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void anEmptyOutboxProducesZeroDeliveriesAndNoPublication() {
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.empty());
        StepVerifier.create(relay(2).runOnce()).expectNext(0).verifyComplete();
        verify(outbox, times(1)).claim(eq(NOW), eq(NOW.plusSeconds(60)), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void aPublishFailureSchedulesAnotherAttemptWithoutPretendingItWasDelivered() {
        ClaimedEvent claimed = claim(1);
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claimed));
        when(publisher.publish(event)).thenReturn(Mono.error(new IllegalStateException("broker indisponible")));
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(relay(1).runOnce()).expectNext(0).verifyComplete();
        verify(outbox).reschedule(claimed, NOW.plusSeconds(2), false, "IllegalStateException");
        verify(outbox, never()).markPublished(any(), anyString(), any());
    }

    @Test
    void exhaustedAttemptIsDurablyMarkedAsFailedRatherThanRetriedForever() {
        ClaimedEvent claimed = claim(3);
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claimed));
        when(publisher.publish(event)).thenReturn(Mono.error(new IllegalStateException("refus")));
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(relay(1).runOnce()).expectNext(0).verifyComplete();
        verify(outbox).reschedule(claimed, NOW.plusSeconds(8), true, "IllegalStateException");
    }

    @Test
    @DisplayName("Crash window : Kafka a réussi mais le marquage a échoué ; le même eventId sera renvoyé")
    void failureAfterBrokerAcknowledgementRetainsTheSameEventIdentityForRedelivery() {
        ClaimedEvent claimed = claim(1);
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claimed));
        when(publisher.publish(event)).thenReturn(Mono.empty());
        when(outbox.markPublished(any(), anyString(), any())).thenReturn(Mono.error(new IllegalStateException("Mongo interrompu")));
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(relay(1).runOnce()).expectNext(0).verifyComplete();
        verify(publisher).publish(event);
        verify(outbox).reschedule(eq(claimed), any(), eq(false), anyString());
        assertThat(claimed.event().eventId()).isEqualTo(event.eventId());
    }

    @Test
    void rescheduleFailureIsNotSilentlySwallowed() {
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claim(1)));
        when(publisher.publish(event)).thenReturn(Mono.error(new IllegalStateException("broker")));
        var mongoFailure = new IllegalArgumentException("outbox inaccessible");
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.error(mongoFailure));

        StepVerifier.create(relay(1).runOnce()).expectErrorMatches(error -> error == mongoFailure).verify();
    }

    @Test
    void aStalledBrokerHasABoundedWait() {
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claim(1)));
        when(publisher.publish(event)).thenReturn(Mono.never());
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> relay(1).runOnce())
                .expectSubscription()
                .thenAwait(java.time.Duration.ofSeconds(15))
                .expectNext(0)
                .verifyComplete();
        verify(outbox).reschedule(eq(claim(1)), any(), eq(false), eq("TimeoutException"));
    }

    @Test
    void tooManyExpiredLeasesAreStoppedEvenIfPreviousWorkersCrashedBeforeRescheduling() {
        ClaimedEvent claimed = claim(4);
        when(outbox.claim(any(), any(), anyString())).thenReturn(Mono.just(claimed));
        when(outbox.reschedule(any(), any(), anyBoolean(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(relay(1).runOnce()).expectNext(0).verifyComplete();
        verify(outbox).reschedule(claimed, NOW, true, "LeaseRecoveryLimit");
        verifyNoInteractions(publisher);
    }
}
