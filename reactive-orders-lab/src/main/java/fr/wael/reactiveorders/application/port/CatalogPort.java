package fr.wael.reactiveorders.application.port;

import fr.wael.reactiveorders.domain.Product;
import reactor.core.publisher.Mono;

public interface CatalogPort { Mono<Product> findProduct(String sku); }
