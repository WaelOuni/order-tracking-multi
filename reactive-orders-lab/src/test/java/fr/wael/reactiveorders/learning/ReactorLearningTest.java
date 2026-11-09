package fr.wael.reactiveorders.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

/**
 * Petites expériences exécutables : ouvrir ce fichier, prédire le résultat, puis lancer le test.
 * Les autres suites testent le métier ; celles-ci rendent visibles les règles de Reactor.
 */
class ReactorLearningTest {

    @BeforeAll
    static void preventAHangingPublisherFromBlockingTheBuild() {
        // Le temps virtuel accélère les tests ; cette limite réelle protège aussi le build
        // si un changement de code empêche le signal attendu d’arriver.
        StepVerifier.setDefaultTimeout(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("defer : construire une chaîne ne déclenche aucun effet ; chaque souscription recommence")
    void coldPublisherIsLazyAndRunsOncePerSubscription() {
        var invocations = new AtomicInteger();
        // La lambda est exécutée à la souscription. Mono.just(invocations.incrementAndGet())
        // incrémenterait immédiatement, avant même que WebFlux ait souscrit à la réponse.
        Mono<Integer> operation = Mono.defer(() -> Mono.just(invocations.incrementAndGet()));

        assertThat(invocations).hasValue(0);
        StepVerifier.create(operation).expectNext(1).verifyComplete();
        StepVerifier.create(operation).expectNext(2).verifyComplete();
        assertThat(invocations).hasValue(2);
    }

    @Test
    @DisplayName("backpressure : le consommateur demande explicitement le nombre d’éléments souhaité")
    void demandControlsHowManyItemsAreEmitted() {
        var emitted = new AtomicInteger();
        // range respecte la demande ; cet exemple ne prétend pas borner le buffer de Kafka
        // ou le réseau HTTP. La backpressure n’existe qu’entre composants compatibles.
        Flux<Integer> numbers = Flux.range(1, 4).doOnNext(value -> emitted.incrementAndGet());

        StepVerifier.create(numbers, 0) // Zéro demande initiale, contrairement au défaut non borné.
                .then(() -> assertThat(emitted).hasValue(0))
                .thenRequest(2)
                .expectNext(1, 2)
                .then(() -> assertThat(emitted).hasValue(2))
                .thenRequest(1)
                .expectNext(3)
                .thenCancel() // La dernière valeur ne sera jamais produite.
                .verify();
        assertThat(emitted).hasValue(3);
    }

    @Test
    @DisplayName("concatMap attend chaque opération et conserve l’ordre de ses résultats")
    void concatMapSerializesAsynchronousOperations() {
        // Construire les publishers temporisés DANS la lambda permet au temps virtuel
        // d’installer son ordonnanceur avant la création de Mono.delay.
        StepVerifier.withVirtualTime(() -> Flux.just(1, 2, 3)
                        .concatMap(value -> Mono.delay(Duration.ofSeconds(4 - value))
                                .thenReturn(value)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(3))
                .expectNext(1)
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(2)
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(3)
                .verifyComplete();
    }

    @Test
    @DisplayName("flatMap permet la concurrence ; il ne conserve pas l’ordre d’achèvement")
    void flatMapCanCompleteInAnotherOrderButConcurrencyIsExplicitlyBounded() {
        StepVerifier.withVirtualTime(() -> Flux.just(1, 2, 3)
                        // Trois souscriptions au maximum. Ne pas lancer un flatMap sans
                        // réfléchir à la capacité de la base ou du service distant.
                        .flatMap(value -> Mono.delay(Duration.ofSeconds(4 - value))
                                .thenReturn(value), 3))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(3))
                .expectNext(3, 2, 1)
                .verifyComplete();
    }

    @Test
    @DisplayName("retryWhen : seuls les échecs transitoires sont réessayés, avec une limite")
    void transientFailuresAreRetriedWithVirtualTime() {
        var attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> Mono.defer(() -> attempts.incrementAndGet() < 3
                                ? Mono.<String>error(new IOException("service temporairement indisponible"))
                                : Mono.just("catalogue retrouvé"))
                        // Deux retries = trois tentatives au total. Jitter neutralisé uniquement
                        // pour rendre l’expérience déterministe ; la production utilise du jitter.
                        .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                                .jitter(0)
                                .filter(IOException.class::isInstance)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(3))
                .expectNext("catalogue retrouvé")
                .verifyComplete();
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("une erreur métier ne doit pas être réessayée comme une panne réseau")
    void businessFailureIsNotRetried() {
        var attempts = new AtomicInteger();
        Mono<String> operation = Mono.defer(() -> {
                    attempts.incrementAndGet();
                    return Mono.<String>error(new IllegalArgumentException("quantité invalide"));
                })
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).filter(IOException.class::isInstance));

        StepVerifier.create(operation)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("quantité invalide"))
                .verify();
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("annulation : quitter un flux propage le signal de cancellation à sa source")
    void cancellationDisposesTheSource() {
        var cancelled = new AtomicBoolean();
        StepVerifier.withVirtualTime(() -> Flux.interval(Duration.ofSeconds(1))
                        // Ce hook représente le nettoyage observable d’une ressource.
                        .doOnCancel(() -> cancelled.set(true)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(0L, 1L)
                .thenCancel()
                .verify();
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("switchIfEmpty n’est pas un traitement d’erreur et son fallback doit rester paresseux")
    void emptyAndErrorAreDifferentSignals() {
        var fallbackCalls = new AtomicInteger();
        Mono<String> fallback = Mono.defer(() -> {
            fallbackCalls.incrementAndGet();
            return Mono.just("valeur de secours");
        });

        StepVerifier.create(Mono.just("présent").switchIfEmpty(fallback))
                .expectNext("présent")
                .verifyComplete();
        assertThat(fallbackCalls).hasValue(0);
        StepVerifier.create(Mono.<String>empty().switchIfEmpty(fallback))
                .expectNext("valeur de secours")
                .verifyComplete();
        StepVerifier.create(Mono.<String>error(new IOException("base inaccessible"))
                        .switchIfEmpty(fallback))
                .expectError(IOException.class)
                .verify();
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    @DisplayName("thenReturn attend le succès ; il ne masque pas une erreur en amont")
    void thenReturnDoesNotTurnAnErrorIntoASuccess() {
        StepVerifier.create(Mono.error(new IOException("publication refusée")).thenReturn("OK"))
                .expectError(IOException.class)
                .verify();
    }
}
