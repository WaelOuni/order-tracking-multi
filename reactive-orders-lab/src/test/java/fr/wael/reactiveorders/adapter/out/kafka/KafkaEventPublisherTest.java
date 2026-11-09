package fr.wael.reactiveorders.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.wael.reactiveorders.domain.OrderEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventPublisherTest {
    private static OrderEvent event() {
        return new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", Instant.now(), "KEYBOARD", 2, 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sendIsLazyIsolatedFromAnEventLoopAndWaitsForItsFuture() throws Exception {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var acknowledgement = new CompletableFuture<SendResult<String, String>>();
        var invoked = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        var onNonBlockingThread = new AtomicReference<Boolean>();
        OrderEvent event = event();
        when(template.send(eq("orders.events.v1"), eq(event.orderId().toString()), anyString()))
                .thenAnswer(invocation -> {
                    thread.set(Thread.currentThread().getName());
                    onNonBlockingThread.set(Schedulers.isInNonBlockingThread());
                    invoked.countDown();
                    return acknowledgement;
                });
        var publisher = new KafkaEventPublisher(template, JsonMapper.builder().build(), "orders.events.v1");
        var result = publisher.publish(event);
        verifyNoInteractions(template);

        StepVerifier.create(result)
                .then(() -> {
                    try { assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    assertThat(thread.get()).startsWith("boundedElastic-");
                    assertThat(onNonBlockingThread.get()).isFalse();
                    // Le broker n’a toujours pas répondu ; terminer la future déclenche onComplete.
                    acknowledgement.complete(null);
                })
                .expectComplete().verify(Duration.ofSeconds(5));
        verify(template).send(eq("orders.events.v1"), eq(event.orderId().toString()), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void cancellingTheSubscriberDoesNotClaimToCancelTheAlreadySentRecord() throws Exception {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var acknowledgement = new CompletableFuture<SendResult<String, String>>();
        var invoked = new CountDownLatch(1);
        when(template.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            invoked.countDown();
            return acknowledgement;
        });
        var publisher = new KafkaEventPublisher(template, JsonMapper.builder().build(), "orders.events.v1");
        StepVerifier.create(publisher.publish(event()))
                .then(() -> {
                    try { assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                })
                .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(acknowledgement).isNotCancelled();
        acknowledgement.complete(null);
    }
}
