package fr.wael.reactiveorders.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("outbox")
@CompoundIndexes({
    @CompoundIndex(name="outbox_ready", def="{'state': 1, 'nextAttemptAt': 1, 'createdAt': 1}"),
    @CompoundIndex(name="outbox_expired_lease", def="{'state': 1, 'leaseUntil': 1}")
})
public record OutboxDocument(@Id String id, StoredEvent event, String state, int attempts,
                             Instant createdAt, Instant nextAttemptAt, String leaseToken, Instant leaseUntil,
                             String lastError, @Indexed(expireAfter = "0s") Instant expiresAt) {}
