package fr.wael.reactiveorders.application.port;

import fr.wael.reactiveorders.domain.OrderEvent;
import reactor.core.publisher.Mono;

public interface EventPublisher { Mono<Void> publish(OrderEvent event); }
