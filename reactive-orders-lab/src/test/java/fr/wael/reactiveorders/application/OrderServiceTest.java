package fr.wael.reactiveorders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import fr.wael.reactiveorders.application.port.*;
import fr.wael.reactiveorders.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OrderServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ID = UUID.fromString("260f6000-0607-4cad-87a1-dc95ef3fe44e");
    private static final Product PRODUCT = new Product("KEYBOARD", "Clavier", 6_990, true);
    private static final CreateOrder COMMAND = new CreateOrder(ID, "CLIENT-DEMO", "KEYBOARD", 2);
    private OrderStore orders;
    private CatalogPort catalogue;
    private OutboxStore outbox;
    private AtomicInteger transactionsSubscribed;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderStore.class);
        catalogue = mock(CatalogPort.class);
        outbox = mock(OutboxStore.class);
        transactionsSubscribed = new AtomicInteger();
        // Ce faux teste l’orchestration, PAS l’atomicité Mongo. FullStackIT vérifie le rollback réel.
        UnitOfWork transactions = new UnitOfWork() {
            @Override
            public <T> Mono<T> transactional(Mono<T> work) {
                return Mono.defer(() -> {
                    transactionsSubscribed.incrementAndGet();
                    return work;
                });
            }
        };
        service = new OrderService(orders, catalogue, outbox, transactions, CLOCK);
    }

    private Order persisted(OrderStatus status, long version) {
        return new Order(ID, "CLIENT-DEMO", "KEYBOARD", "Clavier", 2, 6_990, status, NOW, version);
    }

    private void prepareNewOrder() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.just(PRODUCT));
        when(orders.insert(any())).thenReturn(Mono.just(persisted(OrderStatus.CREATED, 0)));
        when(outbox.append(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Aucun port n’est appelé avant souscription ; la commande et l’événement sont composés ensemble")
    void createIsLazyAndComposesTheOutbox() {
        prepareNewOrder();
        Mono<Order> result = service.create(COMMAND);
        // Les stubs ne sont pas des invocations ; clearInvocations n’est donc pas nécessaire.
        verifyNoInteractions(orders, catalogue, outbox);
        assertThat(transactionsSubscribed).hasValue(0);

        StepVerifier.create(result)
                .assertNext(order -> assertThat(order).isEqualTo(persisted(OrderStatus.CREATED, 0)))
                .verifyComplete();
        verify(orders).insert(argThat(order -> order.version() == null && order.matches("CLIENT-DEMO", "KEYBOARD", 2)));
        verify(outbox).append(argThat(event -> event.type().equals("ORDER_CREATED")
                && event.orderId().equals(ID) && event.aggregateVersion() == 0 && event.occurredAt().equals(NOW)));
        assertThat(transactionsSubscribed).hasValue(1);
    }

    @Test
    void repeatingAnIdenticalRequestReturnsExistingOrderWithoutAnyNewEffect() {
        Order existing = persisted(OrderStatus.CREATED, 0);
        when(orders.findById(ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.create(COMMAND)).expectNext(existing).verifyComplete();
        verify(orders, never()).insert(any());
        verifyNoInteractions(catalogue, outbox);
        assertThat(transactionsSubscribed).hasValue(0);
    }

    @Test
    void repeatingTheKeyAfterCancellationDoesNotRecreateTheOrder() {
        Order existing = persisted(OrderStatus.CANCELLED, 1);
        when(orders.findById(ID)).thenReturn(Mono.just(existing));
        StepVerifier.create(service.create(COMMAND)).expectNext(existing).verifyComplete();
        verifyNoInteractions(catalogue, outbox);
    }

    @Test
    void existingIdempotencyKeyWithDifferentPayloadIsAConflict() {
        when(orders.findById(ID)).thenReturn(Mono.just(persisted(OrderStatus.CREATED, 0)));
        CreateOrder different = new CreateOrder(ID, "CLIENT-DEMO", "KEYBOARD", 3);

        StepVerifier.create(service.create(different)).expectError(Errors.Conflict.class).verify();
        verifyNoInteractions(catalogue, outbox);
        verify(orders, never()).insert(any());
    }

    @Test
    @DisplayName("Course à l’insertion : l’index unique tranche puis le cas d’usage relit le gagnant")
    void concurrentDuplicateIsResolvedAfterTheFailedTransaction() {
        Order winner = persisted(OrderStatus.CREATED, 0);
        when(orders.findById(ID)).thenReturn(Mono.empty(), Mono.just(winner));
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.just(PRODUCT));
        when(orders.insert(any())).thenReturn(Mono.error(new Errors.DuplicateOrder()));

        StepVerifier.create(service.create(COMMAND)).expectNext(winner).verifyComplete();
        verify(orders, times(2)).findById(ID);
        verify(orders, times(1)).insert(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void missingCatalogueItemDoesNotPersistAnything() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.empty());

        StepVerifier.create(service.create(COMMAND)).expectError(Errors.NotFound.class).verify();
        verify(orders, never()).insert(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void unavailableCatalogueIsNotMistakenForAMissingProduct() {
        var outage = new Errors.CatalogUnavailable(new IllegalStateException("timeout"));
        when(orders.findById(ID)).thenReturn(Mono.empty());
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.error(outage));

        StepVerifier.create(service.create(COMMAND)).expectErrorMatches(error -> error == outage).verify();
        verify(orders, never()).insert(any());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Une erreur outbox reste une erreur ; le service ne rend pas de faux succès")
    void outboxFailurePropagatesOutOfTheUnitOfWork() {
        prepareNewOrder();
        var failure = new IllegalStateException("écriture outbox impossible");
        when(outbox.append(any())).thenReturn(Mono.error(failure));

        StepVerifier.create(service.create(COMMAND)).expectErrorMatches(error -> error == failure).verify();
        assertThat(transactionsSubscribed).hasValue(1);
    }

    @Test
    void getTranslatesAbsenceToAnExplicitApplicationError() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        StepVerifier.create(service.get(ID)).expectError(Errors.NotFound.class).verify();
    }

    @Test
    void cancelPersistsATransitionAndItsVersionedEvent() {
        when(orders.findById(ID)).thenReturn(Mono.just(persisted(OrderStatus.CREATED, 0)));
        Order cancelled = persisted(OrderStatus.CANCELLED, 1);
        when(orders.save(any())).thenReturn(Mono.just(cancelled));
        when(outbox.append(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.cancel(ID)).expectNext(cancelled).verifyComplete();
        verify(orders).save(argThat(order -> order.status() == OrderStatus.CANCELLED && order.version() == 0L));
        verify(outbox).append(argThat(event -> event.type().equals("ORDER_CANCELLED") && event.aggregateVersion() == 1));
        assertThat(transactionsSubscribed).hasValue(1);
    }

    @Test
    void repeatedCancellationDoesNotSaveOrPublishAgain() {
        Order cancelled = persisted(OrderStatus.CANCELLED, 1);
        when(orders.findById(ID)).thenReturn(Mono.just(cancelled));

        StepVerifier.create(service.cancel(ID)).expectNext(cancelled).verifyComplete();
        verify(orders, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void staleVersionConflictMustNotProduceAnEvent() {
        when(orders.findById(ID)).thenReturn(Mono.just(persisted(OrderStatus.CREATED, 0)));
        when(orders.save(any())).thenReturn(Mono.error(new Errors.Conflict("version périmée")));

        StepVerifier.create(service.cancel(ID)).expectError(Errors.Conflict.class).verify();
        verifyNoInteractions(outbox);
    }

    @Test
    void paginationRequestsOneExtraRowToDetectNextPageWithoutCount() {
        Order first = persisted(OrderStatus.CREATED, 0);
        when(orders.list(2, 3)).thenReturn(Flux.just(first, first, first));

        StepVerifier.create(service.list(1, 2))
                .assertNext(page -> {
                    assertThat(page.items()).hasSize(2);
                    assertThat(page.page()).isEqualTo(1);
                    assertThat(page.size()).isEqualTo(2);
                    assertThat(page.hasNext()).isTrue();
                })
                .verifyComplete();
        verify(orders).list(2, 3);
    }

    @Test
    void anIncompletePageHasNoFollowingPage() {
        when(orders.list(0, 11)).thenReturn(Flux.just(persisted(OrderStatus.CREATED, 0)));
        StepVerifier.create(service.list(0, 10))
                .assertNext(page -> {
                    assertThat(page.items()).hasSize(1);
                    assertThat(page.hasNext()).isFalse();
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "1001,10", "0,0", "0,51"})
    void invalidPaginationIsRejectedBeforeQueryingMongo(int page, int size) {
        StepVerifier.create(service.list(page, size)).expectError(IllegalArgumentException.class).verify();
        verifyNoInteractions(orders);
    }
}
