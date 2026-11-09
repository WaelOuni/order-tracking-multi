package fr.wael.reactiveorders.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Agrégat immuable : ses invariants valent même lorsqu'aucun contrôleur HTTP n'est impliqué. */
public record Order(UUID id, String customerRef, String sku, String productName, int quantity,
                    long unitPriceCents, OrderStatus status, Instant createdAt, Long version) {
    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        // BSON Date conserve les millisecondes : même précision avant/après un aller-retour Mongo.
        createdAt = createdAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (customerRef == null || customerRef.isBlank() || customerRef.length() > 64)
            throw new DomainRuleException("Référence client obligatoire (64 caractères maximum).");
        if (quantity < 1 || quantity > 100) throw new DomainRuleException("Quantité attendue : 1 à 100.");
        // La représentation monétaire en centimes évite les erreurs d'arrondi de double.
        new Product(sku, productName, unitPriceCents, true);
        if (version != null && version < 0) throw new DomainRuleException("Version invalide.");
    }

    public static Order create(UUID id, String customerRef, Product product, int quantity, Instant now) {
        if (!product.available()) throw new DomainRuleException("Produit indisponible.");
        // null distingue un nouvel agrégat d'un document Mongo déjà persisté avec une version 0.
        return new Order(id, customerRef, product.sku(), product.name(), quantity,
                product.unitPriceCents(), OrderStatus.CREATED, now, null);
    }

    public long totalCents() { return Math.multiplyExact(unitPriceCents, quantity); }

    public Order cancel() {
        // Annulation idempotente : répéter la commande ne produit pas une seconde transition.
        if (status == OrderStatus.CANCELLED) return this;
        return new Order(id, customerRef, sku, productName, quantity,
                unitPriceCents, OrderStatus.CANCELLED, createdAt, version);
    }

    public boolean matches(String customer, String productSku, int count) {
        return customerRef.equals(customer) && sku.equals(productSku) && quantity == count;
    }
}
