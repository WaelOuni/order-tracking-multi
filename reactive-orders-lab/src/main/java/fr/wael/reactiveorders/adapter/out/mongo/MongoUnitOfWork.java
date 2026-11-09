package fr.wael.reactiveorders.adapter.out.mongo;

import com.mongodb.MongoException;
import fr.wael.reactiveorders.application.Errors;
import fr.wael.reactiveorders.application.port.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
public class MongoUnitOfWork implements UnitOfWork {
    private final TransactionalOperator operator;
    public MongoUnitOfWork(TransactionalOperator operator) { this.operator = operator; }
    @Override public <T> Mono<T> transactional(Mono<T> work) {
        // La session Mongo voyage dans Reactor Context, pas dans un ThreadLocal : ne pas souscrire ici.
        return operator.transactional(work)
                .onErrorMap(this::isWriteConflict, error -> new Errors.Conflict("Écriture concurrente ; recharge puis réessaie."));
    }
    private boolean isWriteConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof MongoException mongo && mongo.getCode() == 112) return true;
        }
        return false;
    }
}
