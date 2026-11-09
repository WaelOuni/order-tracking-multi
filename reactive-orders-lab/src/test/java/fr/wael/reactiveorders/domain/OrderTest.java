package fr.wael.reactiveorders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests du métier sans Spring, Reactor ni mocks : le domaine n’a besoin d’aucun conteneur. */
class OrderTest {
    private static final UUID ID = UUID.fromString("260f6000-0607-4cad-87a1-dc95ef3fe44e");
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Product KEYBOARD = new Product("KEYBOARD", "Clavier mécanique", 6_990, true);

    @Test
    @DisplayName("Étant donné un produit disponible, créer la commande fige son prix et son nom")
    void givenAvailableProductWhenCreatingThenKeepTheCatalogueSnapshot() {
        Order order = Order.create(ID, "CLIENT-DEMO", KEYBOARD, 2, NOW);

        assertThat(order.id()).isEqualTo(ID);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.unitPriceCents()).isEqualTo(6_990);
        assertThat(order.productName()).isEqualTo("Clavier mécanique");
        assertThat(order.totalCents()).isEqualTo(13_980);
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.version()).isNull(); // Mongo attribuera la première version à l’insertion.
    }

    @ParameterizedTest(name = "quantité {0} refusée même en dehors d’une requête HTTP")
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void invalidQuantityCannotEnterTheAggregate(int quantity) {
        assertThatThrownBy(() -> Order.create(ID, "CLIENT-DEMO", KEYBOARD, quantity, NOW))
                .isInstanceOf(DomainRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void quantityBoundariesAreInclusive(int quantity) {
        assertThat(Order.create(ID, "CLIENT-DEMO", KEYBOARD, quantity, NOW).quantity())
                .isEqualTo(quantity);
    }

    @Test
    void unavailableProductCannotBeOrdered() {
        Product unavailable = new Product("OUT_OF_STOCK", "Produit indisponible", 100, false);
        assertThatThrownBy(() -> Order.create(ID, "CLIENT-DEMO", unavailable, 1, NOW))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("Produit indisponible.");
    }

    @Test
    @DisplayName("L’annulation est immuable et sa répétition n’ajoute pas une nouvelle transition")
    void cancellationIsAnIdempotentDomainTransition() {
        Order created = Order.create(ID, "CLIENT-DEMO", KEYBOARD, 2, NOW);
        Order cancelled = created.cancel();

        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.id()).isEqualTo(created.id());
        assertThat(cancelled.createdAt()).isEqualTo(created.createdAt());
        assertThat(cancelled.totalCents()).isEqualTo(created.totalCents());
        assertThat(cancelled.cancel()).isSameAs(cancelled);
    }

    @Test
    @DisplayName("La clé d’idempotence ne peut pas être réutilisée pour une autre demande")
    void matchingARequestChecksAllInputFields() {
        Order order = Order.create(ID, "CLIENT-DEMO", KEYBOARD, 2, NOW);
        assertThat(order.matches("CLIENT-DEMO", "KEYBOARD", 2)).isTrue();
        assertThat(order.matches("AUTRE-CLIENT", "KEYBOARD", 2)).isFalse();
        assertThat(order.matches("CLIENT-DEMO", "MOUSE", 2)).isFalse();
        assertThat(order.matches("CLIENT-DEMO", "KEYBOARD", 3)).isFalse();
    }

    @Test
    void moneyUsesLongCentsWithoutIntegerOverflowOrFloatingPointRounding() {
        Product expensive = new Product("EXPENSIVE", "Produit onéreux", 100_000_000L, true);
        assertThat(Order.create(ID, "CLIENT-DEMO", expensive, 100, NOW).totalCents())
                .isEqualTo(10_000_000_000L);
    }

    @Test
    void customerReferenceIsRequiredAndBounded() {
        for (String customer : new String[] {null, "", "   ", "X".repeat(65)}) {
            assertThatThrownBy(() -> Order.create(ID, customer, KEYBOARD, 1, NOW))
                    .isInstanceOf(DomainRuleException.class);
        }
        assertThat(Order.create(ID, "X".repeat(64), KEYBOARD, 1, NOW).customerRef()).hasSize(64);
    }
}
