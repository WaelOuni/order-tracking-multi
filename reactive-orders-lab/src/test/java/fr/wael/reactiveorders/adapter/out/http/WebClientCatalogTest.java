package fr.wael.reactiveorders.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import fr.wael.reactiveorders.application.Errors;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Un vrai échange HTTP contrôle le contrat du client ; les réponses distantes sont déterministes. */
class WebClientCatalogTest {
    private static final String PRODUCT = """
            {"sku":"KEYBOARD","name":"Clavier","unitPriceCents":6990,"available":true}
            """;
    private MockWebServer server;
    private WebClientCatalog catalogue;

    @BeforeEach
    void startHttpServer() throws IOException {
        server = new MockWebServer();
        server.start();
        catalogue = new WebClientCatalog(WebClient.builder().baseUrl(server.url("/").toString()).build());
    }

    @AfterEach
    void stopHttpServer() throws IOException { server.shutdown(); }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void getIsLazyAndDecodesTheProduct() throws InterruptedException {
        server.enqueue(json(PRODUCT));
        var result = catalogue.findProduct("KEYBOARD");
        assertThat(server.getRequestCount()).isZero();
        StepVerifier.create(result).assertNext(product -> {
            assertThat(product.sku()).isEqualTo("KEYBOARD");
            assertThat(product.unitPriceCents()).isEqualTo(6990);
        }).expectComplete().verify(Duration.ofSeconds(5));
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/products/KEYBOARD");
    }

    @Test
    @DisplayName("404 est une absence métier : aucune seconde requête n’est envoyée")
    void doesNotRetry404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectError(Errors.NotFound.class).verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesATransientServerFailureAndRecovers() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json(PRODUCT));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void stopsAfterTwoRetriesAndReportsCatalogueUnavailable() {
        for (int attempt = 0; attempt < 3; attempt++) server.enqueue(new MockResponse().setResponseCode(503));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectError(Errors.CatalogUnavailable.class).verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void doesNotRetryOtherClientErrors() {
        server.enqueue(new MockResponse().setResponseCode(403));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectError(Errors.CatalogUnavailable.class).verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void invalidJsonDoesNotBecomeAFabricatedProduct() {
        server.enqueue(json("{broken-json"));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectError(Errors.CatalogUnavailable.class).verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mismatchedSkuIsRejectedEvenWithA200Response() {
        for (int attempt = 0; attempt < 3; attempt++) server.enqueue(json(PRODUCT.replace("KEYBOARD", "MOUSE")));
        StepVerifier.create(catalogue.findProduct("KEYBOARD"))
                .expectError(Errors.CatalogUnavailable.class).verify(Duration.ofSeconds(5));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }
}
