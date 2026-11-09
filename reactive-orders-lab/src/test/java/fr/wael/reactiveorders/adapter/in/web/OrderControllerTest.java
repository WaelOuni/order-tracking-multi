package fr.wael.reactiveorders.adapter.in.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.wael.reactiveorders.application.*;
import fr.wael.reactiveorders.application.port.*;
import fr.wael.reactiveorders.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Vraie couche WebFlux/validation/JSON avec les ports d’I/O simulés.
 * Aucun serveur Mongo/Kafka ; FullStackIT couvre la vraie configuration complète.
 */
class OrderControllerTest {
    private static final UUID ID = UUID.fromString("260f6000-0607-4cad-87a1-dc95ef3fe44e");
    private static final Order ORDER = new Order(ID, "CLIENT-DEMO", "KEYBOARD", "Clavier", 2, 6_990,
            OrderStatus.CREATED, Instant.parse("2026-11-09T10:00:00Z"), 0L);
    private OrderStore orders;
    private CatalogPort catalogue;
    private OutboxStore outbox;
    private WebTestClient http;

    @BeforeEach
    void setUp() {
        orders = mock(OrderStore.class);
        catalogue = mock(CatalogPort.class);
        outbox = mock(OutboxStore.class);
        UnitOfWork transactions = new UnitOfWork() {
            public <T> Mono<T> transactional(Mono<T> work) { return work; }
        };
        var service = new OrderService(orders, catalogue, outbox, transactions, Clock.systemUTC());
        http = WebTestClient.bindToController(new OrderController(service))
                .controllerAdvice(new ApiExceptionHandler()).build();
    }

    private Map<String, Object> payload(int quantity) {
        return Map.of("customerRef", "CLIENT-DEMO", "sku", "KEYBOARD", "quantity", quantity);
    }

    @Test
    void returnsAnExplicitOrderDtoAndItsMonetaryTotal() {
        when(orders.findById(ID)).thenReturn(Mono.just(ORDER));
        http.get().uri("/api/orders/{id}", ID).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(ID.toString())
                .jsonPath("$.totalCents").isEqualTo(13_980)
                .jsonPath("$.status").isEqualTo("CREATED");
    }

    @Test
    void identicalCreateReturnsTheLocationOfItsResource() {
        when(orders.findById(ID)).thenReturn(Mono.just(ORDER));
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue(payload(2)).exchange()
                .expectStatus().isCreated().expectHeader().valueEquals("Location", "/api/orders/" + ID);
        verifyNoInteractions(catalogue, outbox);
    }

    @Test
    void validationRejectsInvalidQuantityBeforeTheApplicationTouchesItsPorts() {
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue(payload(0)).exchange()
                .expectStatus().isBadRequest().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(400).jsonPath("$.traceId").isNotEmpty();
        verifyNoInteractions(orders, catalogue, outbox);
    }

    @Test
    void malformedJsonIsAClientErrorRatherThanAnInternalError() {
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue("{\"customerRef\":").exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.status").isEqualTo(400);
        verifyNoInteractions(orders, catalogue, outbox);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).bodyValue(payload(2)).exchange()
                .expectStatus().isBadRequest();
        verifyNoInteractions(orders, catalogue, outbox);
    }

    @Test
    void invalidUuidInPathIsRejected() {
        http.get().uri("/api/orders/not-a-uuid").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(orders);
    }

    @Test
    void unknownOrderHas404ProblemDetails() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        http.get().uri("/api/orders/{id}", ID).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/api/orders/" + ID);
    }

    @Test
    void anIdempotencyConflictHas409ProblemDetails() {
        when(orders.findById(ID)).thenReturn(Mono.just(ORDER));
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue(payload(3)).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo(409);
    }

    @Test
    void aCatalogueOutageHas503InsteadOfFalseBusinessSuccess() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.error(new Errors.CatalogUnavailable(new RuntimeException())));
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue(payload(2)).exchange()
                .expectStatus().isEqualTo(503).expectBody().jsonPath("$.status").isEqualTo(503);
        verify(orders, never()).insert(any());
    }

    @Test
    void aDomainRuleViolationHas422() {
        when(orders.findById(ID)).thenReturn(Mono.empty());
        when(catalogue.findProduct("KEYBOARD")).thenReturn(Mono.just(new Product("KEYBOARD", "Clavier", 6990, false)));
        http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", ID.toString()).bodyValue(payload(2)).exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void paginationLimitIsEnforcedAtTheHttpBoundary() {
        http.get().uri("/api/orders?page=0&size=51").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("Un POST simple sans JSON n’est pas accepté comme commande de modification")
    void cancellationRequiresTheDocumentedJsonContentTypeEvenWithoutABody() {
        http.post().uri("/api/orders/{id}/cancel", ID).exchange().expectStatus().isEqualTo(415);
        verifyNoInteractions(orders);
    }

    @Test
    void cancellationAcceptsJsonContentTypeWithNoBody() {
        Order cancelled = ORDER.cancel();
        when(orders.findById(ID)).thenReturn(Mono.just(cancelled));
        http.post().uri("/api/orders/{id}/cancel", ID).contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Les détails internes d’une exception ne sont jamais exposés dans la réponse")
    void internalFailureIsSanitized() {
        when(orders.findById(ID)).thenReturn(Mono.error(new RuntimeException("mongodb://private-host/password")));
        http.get().uri("/api/orders/{id}", ID).exchange().expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.detail").isEqualTo("Erreur interne ; utilise l'identifiant de requête pour le diagnostic.")
                .jsonPath("$.traceId").isNotEmpty();
    }
}
