package fr.wael.reactiveorders.adapter.out.http;

import fr.wael.reactiveorders.application.Errors;
import fr.wael.reactiveorders.application.port.CatalogPort;
import fr.wael.reactiveorders.domain.Product;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class WebClientCatalog implements CatalogPort {
    private final WebClient catalogWebClient;
    public WebClientCatalog(WebClient catalogWebClient) { this.catalogWebClient = catalogWebClient; }
    @Override public Mono<Product> findProduct(String sku) {
        return Mono.defer(() -> catalogWebClient.get()
                        // Un segment de chemin encodé, jamais une URL venant du client : limite les risques SSRF.
                        .uri(builder -> builder.pathSegment("products", sku).build())
                        .retrieve()
                        .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                                response -> response.releaseBody().thenReturn(new Errors.NotFound("Produit introuvable.")))
                        .onStatus(status -> status.is5xxServerError(),
                                response -> response.releaseBody().thenReturn(new TemporaryCatalogFailure()))
                        .bodyToMono(Product.class)
                        .switchIfEmpty(Mono.error(new TemporaryCatalogFailure()))
                        .filter(product -> sku.equals(product.sku()))
                        .switchIfEmpty(Mono.error(new TemporaryCatalogFailure())))
                // Timeout par tentative : un appel sortant ne monopolise pas des ressources indéfiniment.
                .timeout(Duration.ofSeconds(2))
                // GET est idempotent. Aucun retry aveugle sur les commandes POST ou les erreurs métier 404/4xx.
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100)).maxBackoff(Duration.ofMillis(500))
                        .filter(error -> error instanceof TemporaryCatalogFailure
                                || error instanceof WebClientRequestException || error instanceof TimeoutException)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // Une réponse invalide ou une panne est un 503, jamais un produit fictif de secours.
                .onErrorMap(error -> !(error instanceof Errors.NotFound), Errors.CatalogUnavailable::new);
    }
    private static class TemporaryCatalogFailure extends RuntimeException {}
}
