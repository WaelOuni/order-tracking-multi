package fr.wael.reactiveorders.adapter.in.web;

import fr.wael.reactiveorders.application.Errors;
import fr.wael.reactiveorders.domain.DomainRuleException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception error, ServerWebExchange exchange) {
        HttpStatus status;
        String detail;
        if (error instanceof WebExchangeBindException) {
            status = HttpStatus.BAD_REQUEST; detail = "Vérifie la référence client, le SKU et la quantité (1 à 100).";
        } else if (error instanceof Errors.NotFound) {
            status = HttpStatus.NOT_FOUND; detail = error.getMessage();
        } else if (error instanceof Errors.Conflict) {
            status = HttpStatus.CONFLICT; detail = error.getMessage();
        } else if (error instanceof DomainRuleException) {
            status = HttpStatus.UNPROCESSABLE_ENTITY; detail = error.getMessage();
        } else if (error instanceof Errors.CatalogUnavailable) {
            status = HttpStatus.SERVICE_UNAVAILABLE; detail = error.getMessage();
        } else if (error instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST; detail = error.getMessage();
        } else if (error instanceof ResponseStatusException response) {
            status = HttpStatus.valueOf(response.getStatusCode().value()); detail = status.getReasonPhrase();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR; detail = "Erreur interne ; utilise l'identifiant de requête pour le diagnostic.";
            LOG.error("Request {} failed: {}", exchange.getRequest().getId(), error.getClass().getSimpleName());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("traceId", exchange.getRequest().getId());
        return problem; // RFC 9457 ; aucune stacktrace, URI Mongo ou payload Kafka exposé au navigateur.
    }
}
