package fr.wael.reactiveorders.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.wael.reactiveorders.application.*;
import fr.wael.reactiveorders.application.port.*;
import fr.wael.reactiveorders.domain.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Intégration réelle : serveur HTTP Netty, Mongo en replica set et Kafka KRaft.
 * Exécuter avec ./mvnw verify -Pintegration. L’absence de Docker fait échouer la suite :
 * aucun disabledWithoutDocker ne peut transformer une absence de preuve en succès.
 * Les attentes bloquantes bornées sont uniquement ici, sur les threads JUnit.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.enabled=false", "app.kafka.listener-enabled=true"})
class FullStackIT {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String TOPIC = "orders.events.v1";
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.26").withReplicaSet();
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    static final MockWebServer CATALOGUE = catalogueServer();

    private static MockWebServer catalogueServer() {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/products/KEYBOARD".equals(request.getPath())) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""
                                    {"sku":"KEYBOARD","name":"Clavier mécanique","unitPriceCents":6990,"available":true}
                                    """);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try { server.start(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
        return server;
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        // Les ports sont attribués par Docker : pas de collisions avec les services locaux.
        properties.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("reactive_orders_it"));
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("app.catalog.base-url", () -> CATALOGUE.url("/").toString());
    }

    @LocalServerPort int port;
    @Autowired OrderStore orders;
    @Autowired OutboxStore outbox;
    @Autowired AuditStore audit;
    @Autowired UnitOfWork transactions;
    @Autowired OutboxRelay relay;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired KafkaTemplate<String, String> kafka;
    private WebTestClient http;

    @BeforeEach
    void isolateMutableStateAndBindToTheRealServer() {
        // remove préserve les collections/indexes (drop les détruirait et fausserait les tests).
        mongo.remove(new Query(), "orders")
                .then(mongo.remove(new Query(), "outbox"))
                .then(mongo.remove(new Query(), "audit"))
                .block(WAIT);
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(WAIT).build();
    }

    @AfterAll
    static void stopCatalogue() throws IOException { CATALOGUE.shutdown(); }

    private WebTestClient.ResponseSpec create(UUID id, int quantity) {
        return http.post().uri("/api/orders")
                .header("Idempotency-Key", id.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customerRef", "CLIENT-IT", "sku", "KEYBOARD", "quantity", quantity))
                .exchange();
    }

    private static Order draft(UUID id) {
        return Order.create(id, "CLIENT-IT", new Product("KEYBOARD", "Clavier", 6_990, true), 2, Instant.now());
    }

    @Test
    @DisplayName("BDD : créer, rejouer la même demande, refuser une autre demande puis annuler")
    void givenAnOrderWhenReplayingAndCancellingThenItsIdentityIsPreserved() {
        UUID id = UUID.randomUUID();
        create(id, 2).expectStatus().isCreated()
                .expectBody().jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.totalCents").isEqualTo(13_980)
                .jsonPath("$.status").isEqualTo("CREATED");
        create(id, 2).expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.id").isEqualTo(id.toString());
        create(id, 3).expectStatus().isEqualTo(409);
        http.post().uri("/api/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
        http.post().uri("/api/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo(1);
        http.get().uri("/api/orders?page=0&size=10").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
        // Une création + une annulation, malgré quatre commandes HTTP reçues.
        StepVerifier.create(mongo.count(new Query(), "outbox")).expectNext(2L).expectComplete().verify(WAIT);
    }

    @Test
    @DisplayName("Outbox → Kafka → consommateur Mongo → snapshot SSE, avec les vrais adaptateurs")
    void aCreatedOrderTravelsThroughKafkaAndAppearsInSse() {
        UUID id = UUID.randomUUID();
        create(id, 2).expectStatus().isCreated();
        StepVerifier.create(relay.runOnce()).expectNext(1).expectComplete().verify(WAIT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(audit.recent(20)
                .filter(entry -> entry.orderId().equals(id)).collectList().block(WAIT)).hasSize(1));

        var events = http.get().uri("/api/events").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<List<AuditEntry>>>() {})
                .getResponseBody();
        StepVerifier.create(events.filter(event -> "snapshot".equals(event.event())).next())
                .assertNext(snapshot -> assertThat(snapshot.data())
                        .anySatisfy(entry -> assertThat(entry.orderId()).isEqualTo(id)))
                .expectComplete().verify(WAIT);
        // next() annule le flux après le premier snapshot : on ne laisse pas une connexion infinie.
    }

    @Test
    @DisplayName("Mongo : une panne après les deux écritures annule commande ET outbox")
    void transactionRollsBackBothDocuments() {
        UUID id = UUID.randomUUID();
        var eventId = new AtomicReference<UUID>();
        var failure = new IllegalStateException("échec simulé après les écritures");
        Mono<Order> work = orders.insert(draft(id)).flatMap(saved -> {
            OrderEvent event = OrderEvent.of(saved, "ORDER_CREATED", Instant.now());
            eventId.set(event.eventId());
            return outbox.append(event).then(Mono.<Order>error(failure));
        });

        StepVerifier.create(transactions.transactional(work))
                .expectErrorMatches(error -> error == failure).verify(WAIT);
        StepVerifier.create(orders.findById(id)).expectComplete().verify(WAIT);
        assertThat(eventId.get()).isNotNull();
        StepVerifier.create(mongo.count(new Query(), "outbox")).expectNext(0L).expectComplete().verify(WAIT);
    }

    @Test
    @DisplayName("Mongo : un agrégat lu avec une ancienne version ne peut pas écraser une annulation")
    void optimisticLockingRejectsAStaleSnapshot() {
        Order original = orders.insert(draft(UUID.randomUUID())).block(WAIT);
        assertThat(original).isNotNull();
        StepVerifier.create(orders.save(original.cancel()))
                .assertNext(saved -> assertThat(saved.version()).isEqualTo(1L))
                .expectComplete().verify(WAIT);
        StepVerifier.create(orders.save(original.cancel())).expectError(Errors.Conflict.class).verify(WAIT);
        StepVerifier.create(orders.findById(original.id()))
                .assertNext(saved -> assertThat(saved.status()).isEqualTo(OrderStatus.CANCELLED))
                .expectComplete().verify(WAIT);
    }

    @Test
    @DisplayName("Deux workers en concurrence ne peuvent pas réclamer le même lease actif")
    void claimIsAtomicAndAnExpiredLeaseCannotBeAcknowledgedByItsPreviousOwner() {
        Instant now = Instant.now();
        OrderEvent event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", now, "KEYBOARD", 2, 0);
        outbox.append(event).block(WAIT);
        Instant claimTime = now.plusSeconds(1);
        List<ClaimedEvent> claims = Flux.merge(
                outbox.claim(claimTime, now.plusSeconds(60), "worker-A"),
                outbox.claim(claimTime, now.plusSeconds(60), "worker-B"))
                .collectList().block(WAIT);
        assertThat(claims).hasSize(1);
        ClaimedEvent previous = claims.getFirst();
        ClaimedEvent next = outbox.claim(now.plusSeconds(61), now.plusSeconds(121), "worker-C").block(WAIT);
        assertThat(next).isNotNull();
        assertThat(next.event().eventId()).isEqualTo(event.eventId());
        assertThat(next.attempts()).isEqualTo(2);
        StepVerifier.create(outbox.markPublished(event.eventId(), previous.token(), now.plusSeconds(62)))
                .expectError(Errors.LostLease.class).verify(WAIT);
        StepVerifier.create(outbox.markPublished(event.eventId(), next.token(), now.plusSeconds(62)))
                .expectComplete().verify(WAIT);
    }

    @Test
    @DisplayName("L’unicité de eventId protège l’audit même avec deux insertions simultanées")
    void auditDeduplicatesConcurrentDeliveriesAtomically() {
        var event = new OrderEvent(UUID.randomUUID(), 1, UUID.randomUUID(), "ORDER_CREATED", Instant.now(), "KEYBOARD", 2, 0);
        StepVerifier.create(Mono.when(audit.appendIdempotently(event), audit.appendIdempotently(event)))
                .expectComplete().verify(WAIT);
        StepVerifier.create(audit.recent(20).filter(entry -> entry.eventId().equals(event.eventId())).count())
                .expectNext(1L).expectComplete().verify(WAIT);
    }

    @Test
    @DisplayName("Un message poison est observable en DLT ; il n’est pas enregistré comme un audit valide")
    void poisonMessageReachesTheDeadLetterTopic() throws Exception {
        String poison = "{\"schemaVersion\":999,\"testMarker\":\"" + UUID.randomUUID() + "\"}";
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            kafka.send(TOPIC, "poison-test", poison).get(10, TimeUnit.SECONDS);
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().pollInSameThread().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(received::add);
                assertThat(received).anySatisfy(record -> assertThat(record.value()).isEqualTo(poison));
            });
        }
    }
}
