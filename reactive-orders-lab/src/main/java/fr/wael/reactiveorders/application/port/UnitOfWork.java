package fr.wael.reactiveorders.application.port;

import reactor.core.publisher.Mono;

/** L'application demande l'atomicité sans importer TransactionalOperator. */
public interface UnitOfWork { <T> Mono<T> transactional(Mono<T> work); }
