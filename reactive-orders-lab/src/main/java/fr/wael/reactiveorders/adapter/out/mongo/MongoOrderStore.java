package fr.wael.reactiveorders.adapter.out.mongo;

import fr.wael.reactiveorders.application.Errors;
import fr.wael.reactiveorders.application.port.OrderStore;
import fr.wael.reactiveorders.domain.Order;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MongoOrderStore implements OrderStore {
    private final ReactiveMongoTemplate mongo;
    public MongoOrderStore(ReactiveMongoTemplate mongo) { this.mongo = mongo; }
    @Override public Mono<Order> findById(UUID id) {
        return mongo.findById(id.toString(), OrderDocument.class).map(OrderDocument::toDomain);
    }
    @Override public Mono<Order> insert(Order order) {
        // insert, pas save/upsert : une clé d'idempotence existante ne doit jamais écraser une commande.
        return mongo.insert(OrderDocument.from(order)).map(OrderDocument::toDomain)
                .onErrorMap(DuplicateKeyException.class, error -> new Errors.DuplicateOrder());
    }
    @Override public Mono<Order> save(Order order) {
        // Spring ajoute la version au filtre de mise à jour et l'incrémente atomiquement.
        return mongo.save(OrderDocument.from(order)).map(OrderDocument::toDomain)
                .onErrorMap(OptimisticLockingFailureException.class,
                        error -> new Errors.Conflict("La commande a changé ; recharge-la avant de réessayer."));
    }
    @Override public Flux<Order> list(int offset, int limit) {
        Query query = new Query().with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("_id")))
                .skip(offset).limit(limit);
        // Aucune collectList ici : l'adaptateur retourne le flux du pilote, borné côté serveur.
        return mongo.find(query, OrderDocument.class).map(OrderDocument::toDomain);
    }
}
