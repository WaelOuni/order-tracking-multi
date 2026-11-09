package fr.wael.reactiveorders.application;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(UUID eventId, UUID orderId, String type, Instant occurredAt,
                         Instant recordedAt, String sku, int quantity) {}
