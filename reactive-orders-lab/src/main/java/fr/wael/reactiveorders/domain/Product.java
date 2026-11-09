package fr.wael.reactiveorders.domain;

/** Snapshot du catalogue : disponible ne signifie pas réservation de stock. */
public record Product(String sku, String name, long unitPriceCents, boolean available) {
    public Product {
        if (sku == null || !sku.matches("[A-Z0-9_-]{1,40}")) throw new DomainRuleException("SKU invalide.");
        if (name == null || name.isBlank()) throw new DomainRuleException("Nom du produit obligatoire.");
        if (unitPriceCents < 0 || unitPriceCents > 100_000_000L) throw new DomainRuleException("Prix invalide.");
    }
}
