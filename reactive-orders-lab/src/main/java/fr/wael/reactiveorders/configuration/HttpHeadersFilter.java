package fr.wael.reactiveorders.configuration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Défenses navigateur pour le lab localhost. L'authentification métier reste un chantier avant exposition réseau. */
@Component
public class HttpHeadersFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var headers = exchange.getResponse().getHeaders();
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Request-Id", exchange.getRequest().getId());
        if (exchange.getRequest().getPath().value().startsWith("/api/")) headers.setCacheControl("no-store");
        // Un site tiers ne doit pas utiliser le navigateur pour muter notre service de développement local.
        if ("cross-site".equals(exchange.getRequest().getHeaders().getFirst("Sec-Fetch-Site"))
                && !exchange.getRequest().getMethod().matches("GET") && !exchange.getRequest().getMethod().matches("HEAD")) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
