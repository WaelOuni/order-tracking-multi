package fr.wael.reactiveorders.adapter.in.web;

import fr.wael.reactiveorders.application.AuditEntry;
import fr.wael.reactiveorders.application.port.AuditStore;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class EventsController {
    private final AuditStore audit;
    public EventsController(AuditStore audit) { this.audit = audit; }

    @GetMapping(path="/api/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<List<AuditEntry>>> events() {
        // Chaque connexion lit un snapshot initial puis actualise au plus une fois par seconde.
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                // Si le client est lent, abandonner des ticks de polling : jamais accumuler un buffer infini.
                .onBackpressureDrop()
                // Une seule lecture à la fois, prélecture=1. La requête renvoie au maximum 20 documents.
                .concatMap(tick -> audit.recent(20).collectList(), 1)
                // Un snapshot périodique sert aussi de heartbeat et reconstruit l'état après reconnexion.
                .map(snapshot -> ServerSentEvent.builder(snapshot).event("snapshot").retry(Duration.ofSeconds(3)).build())
                // Limite la vie d'une connexion ; EventSource reconnecte et reçoit un nouveau snapshot.
                .take(Duration.ofMinutes(10));
        // SSE n'est pas Reactive Streams côté navigateur et n'est pas un journal avec replay garanti.
    }
}
