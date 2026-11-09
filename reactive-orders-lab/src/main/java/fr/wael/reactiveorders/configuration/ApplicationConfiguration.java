package fr.wael.reactiveorders.configuration;

import fr.wael.reactiveorders.application.OrderService;
import fr.wael.reactiveorders.application.OutboxRelay;
import fr.wael.reactiveorders.application.port.*;
import io.netty.channel.ChannelOption;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean OrderService orderService(OrderStore orders, CatalogPort catalog, OutboxStore outbox, UnitOfWork tx, Clock clock) {
        return new OrderService(orders, catalog, outbox, tx, clock);
    }
    @Bean OutboxRelay outboxRelay(OutboxStore store, EventPublisher publisher, Clock clock,
                                  @Value("${app.outbox.batch-size:20}") int batch,
                                  @Value("${app.outbox.max-attempts:10}") int attempts) {
        return new OutboxRelay(store, publisher, clock, batch, attempts);
    }
    @Bean ReactiveMongoTransactionManager transactionManager(ReactiveMongoDatabaseFactory factory) {
        // Les transactions multi-documents nécessitent un replica set, fourni par Compose et les IT.
        return new ReactiveMongoTransactionManager(factory);
    }
    @Bean TransactionalOperator transactionalOperator(ReactiveMongoTransactionManager manager) {
        return TransactionalOperator.create(manager);
    }
    @Bean(destroyMethod="dispose") ConnectionProvider catalogConnectionPool() {
        // Réactif ne veut pas dire illimité : bornes du pool ET de la file des acquisitions.
        return ConnectionProvider.builder("catalog").maxConnections(20).pendingAcquireMaxCount(40)
                .pendingAcquireTimeout(Duration.ofSeconds(1)).maxIdleTime(Duration.ofSeconds(20)).build();
    }
    @Bean WebClient catalogWebClient(WebClient.Builder builder, ConnectionProvider catalogConnectionPool,
                                     @Value("${app.catalog.base-url}") String baseUrl) {
        HttpClient http = HttpClient.create(catalogConnectionPool).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2));
        return builder.baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(http))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(64 * 1024)).build();
    }
}
