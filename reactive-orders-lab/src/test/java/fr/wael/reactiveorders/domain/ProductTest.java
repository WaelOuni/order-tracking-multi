package fr.wael.reactiveorders.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"lowercase", "../SECRET", "SKU WITH SPACE", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDE"})
    void rejectSkuOutsideTheContract(String sku) {
        assertThatThrownBy(() -> new Product(sku, "Produit", 100, true))
                .isInstanceOf(DomainRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 100_000_001L, Long.MAX_VALUE})
    void rejectAnInvalidCataloguePrice(long price) {
        assertThatThrownBy(() -> new Product("KEYBOARD", "Produit", price, true))
                .isInstanceOf(DomainRuleException.class);
    }
}
