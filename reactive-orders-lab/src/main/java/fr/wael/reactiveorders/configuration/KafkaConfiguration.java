package fr.wael.reactiveorders.configuration;

import fr.wael.reactiveorders.domain.DomainRuleException;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfiguration {
    @Bean NewTopic ordersTopic(@Value("${app.kafka.topic}") String topic) {
        // 3 partitions pour apprendre la clé de partitionnement ; réplication=1 uniquement pour le lab local.
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
    @Bean NewTopic ordersDlt(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }
    @Bean DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                                @Value("${app.kafka.topic}") String topic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(topic + ".DLT", record.partition()));
        // Ne pas acquitter un poison message si sa publication en DLT a elle-même échoué.
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(15));
        // Deux réessais supplémentaires pour les erreurs temporaires ; les payloads invalides vont directement en DLT.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(DomainRuleException.class, IllegalArgumentException.class, JacksonException.class);
        return handler;
    }
}
