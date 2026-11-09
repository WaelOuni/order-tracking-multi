package fr.wael.reactiveorders.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit")
@CompoundIndex(name="audit_recent", def="{'recordedAt': -1, '_id': 1}")
public record AuditDocument(@Id String id, StoredEvent event, Instant recordedAt) {}
