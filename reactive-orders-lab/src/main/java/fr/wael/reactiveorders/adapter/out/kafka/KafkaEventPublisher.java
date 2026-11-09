package fr.wael.reactiveorders.adapter.out.kafka;

import fr.wael.reactiveorders.application.port.EventPublisher;
import fr.wael.reactiveorders.domain.OrderEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Component
public class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final String topic;
    public KafkaEventPublisher(KafkaTemplate<String, String> template, ObjectMapper mapper,
                               @Value("${app.kafka.topic}") String topic) {
        this.template = template; this.mapper = mapper; this.topic = topic;
    }
    @Override public Mono<Void> publish(OrderEvent event) {
        return Mono.fromCallable(() -> template.send(topic, event.orderId().toString(), mapper.writeValueAsString(event)))
                // send retourne une future MAIS peut attendre métadonnées/buffer : protéger l'event loop.
                // Ce scheduler est borné. Ne pas l'ajouter aux vrais pilotes réactifs Mongo/WebClient.
                .subscribeOn(Schedulers.boundedElastic())
                // Composer la future sans get(), join() ni block(). L'ack broker reste attendu.
                .flatMap(future -> Mono.fromFuture(future, true))
                // suppressCancel=true : un timeout local ne prétend pas révoquer l'envoi réseau déjà lancé.
                .then();
    }
}
