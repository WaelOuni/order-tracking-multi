package fr.wael.reactiveorders.application;

import fr.wael.reactiveorders.domain.OrderEvent;

public record ClaimedEvent(OrderEvent event, String token, int attempts) {}
