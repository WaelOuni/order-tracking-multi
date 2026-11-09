package fr.wael.reactiveorders.adapter.in.kafka;

import fr.wael.reactiveorders.application.port.AuditStore;
import fr.wael.reactiveorders.domain.OrderEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventsListener {
    private final ObjectMapper mapper;
    private final AuditStore audit;
    public OrderEventsListener(ObjectMapper mapper, AuditStore audit) { this.mapper = mapper; this.audit = audit; }

    @KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}",
            autoStartup = "${app.kafka.listener-enabled:true}")
    public Mono<Void> onMessage(String payload) {
        // fromCallable transforme aussi une erreur de JSON en signal onError pris en charge par le container.
        return Mono.fromCallable(() -> mapper.readValue(payload, OrderEvent.class))
                // Le contrat valide schemaVersion et les champs ; le stockage déduplique par eventId.
                .flatMap(audit::appendIdempotently);
        // Spring Kafka souscrit et n'acquitte qu'après succès. Jamais subscribe() puis retour immédiat.
        // Les Mono peuvent finir dans un ordre différent par partition : l'audit est indépendant de cet ordre.
    }
}
