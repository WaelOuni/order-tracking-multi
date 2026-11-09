package fr.wael.reactiveorders.application;

import fr.wael.reactiveorders.application.port.*;
import fr.wael.reactiveorders.domain.Order;
import fr.wael.reactiveorders.domain.OrderEvent;
import fr.wael.reactiveorders.domain.OrderStatus;
import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Cas d'usage DDD : orchestration réactive, règles dans Order, I/O derrière des ports. */
public final class OrderService {
    private final OrderStore orders;
    private final CatalogPort catalog;
    private final OutboxStore outbox;
    private final UnitOfWork transactions;
    private final Clock clock;

    public OrderService(OrderStore orders, CatalogPort catalog, OutboxStore outbox, UnitOfWork transactions, Clock clock) {
        this.orders = orders; this.catalog = catalog; this.outbox = outbox;
        this.transactions = transactions; this.clock = clock;
    }

    public Mono<Order> create(CreateOrder command) {
        // defer rend l'ensemble paresseux : même l'appel aux ports commence à la souscription.
        return Mono.defer(() -> orders.findById(command.requestId())
                // flatMap compose une vérification qui peut produire un signal d'erreur.
                .flatMap(existing -> ensureSameRequest(existing, command))
                // Sans defer, construire l'alternative pourrait lancer des effets trop tôt.
                .switchIfEmpty(Mono.defer(() -> catalog.findProduct(command.sku())
                        .switchIfEmpty(Mono.error(new Errors.NotFound("Produit introuvable.")))
                        // map est réservé à une transformation synchrone et courte, sans I/O.
                        .map(product -> Order.create(command.requestId(), command.customerRef(), product,
                                command.quantity(), clock.instant()))
                        // La transaction contient seulement les deux écritures Mongo, pas l'appel HTTP catalogue.
                        .flatMap(order -> transactions.transactional(orders.insert(order)
                                .flatMap(saved -> outbox.append(OrderEvent.of(saved, "ORDER_CREATED", clock.instant()))
                                        // thenReturn conserve la commande après le Mono<Void> de l'outbox.
                                        .thenReturn(saved))))))
                // Une requête simultanée peut gagner après notre lecture : l'index _id tranche la course.
                // La relecture a lieu APRÈS la sortie de la transaction avortée.
                .onErrorResume(Errors.DuplicateOrder.class, error -> orders.findById(command.requestId())
                        .switchIfEmpty(Mono.error(error))
                        .flatMap(existing -> ensureSameRequest(existing, command))));
    }

    private Mono<Order> ensureSameRequest(Order order, CreateOrder command) {
        return order.matches(command.customerRef(), command.sku(), command.quantity())
                ? Mono.just(order)
                : Mono.error(new Errors.Conflict("Idempotency-Key déjà utilisée avec un contenu différent."));
    }

    public Mono<Order> get(UUID id) {
        return Mono.defer(() -> orders.findById(id))
                // L'absence n'est pas une erreur Mongo ; elle devient ici une erreur applicative explicite.
                .switchIfEmpty(Mono.error(new Errors.NotFound("Commande introuvable.")));
    }

    public Mono<Order> cancel(UUID id) {
        return get(id).flatMap(order -> {
            if (order.status() == OrderStatus.CANCELLED) return Mono.just(order);
            // @Version protège de deux écritures concurrentes ; un conflit est remonté, jamais écrasé.
            return transactions.transactional(orders.save(order.cancel())
                    .flatMap(saved -> outbox.append(OrderEvent.of(saved, "ORDER_CANCELLED", clock.instant()))
                            .thenReturn(saved)));
        });
    }

    public Mono<OrderPage> list(int page, int size) {
        return Mono.defer(() -> {
            if (page < 0 || page > 1000 || size < 1 || size > 50)
                return Mono.error(new IllegalArgumentException("page doit être entre 0 et 1000 ; size entre 1 et 50."));
            // Une ligne de plus permet hasNext sans COUNT global. La requête et la collecte sont bornées.
            return orders.list(page * size, size + 1).take(size + 1L).collectList()
                    .map(found -> new OrderPage(found.subList(0, Math.min(found.size(), size)), page, size, found.size() > size));
        });
    }
}
