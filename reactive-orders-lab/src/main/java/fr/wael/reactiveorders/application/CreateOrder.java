package fr.wael.reactiveorders.application;

import fr.wael.reactiveorders.domain.DomainRuleException;
import java.util.UUID;

public record CreateOrder(UUID requestId, String customerRef, String sku, int quantity) {
    public CreateOrder {
        if (requestId == null || customerRef == null || customerRef.isBlank() || customerRef.length() > 64
                || sku == null || !sku.matches("[A-Z0-9_-]{1,40}") || quantity < 1 || quantity > 100)
            throw new DomainRuleException("Commande invalide.");
    }
}
