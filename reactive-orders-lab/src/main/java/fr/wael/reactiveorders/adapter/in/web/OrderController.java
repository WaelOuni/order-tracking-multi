package fr.wael.reactiveorders.adapter.in.web;

import fr.wael.reactiveorders.application.CreateOrder;
import fr.wael.reactiveorders.application.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OrderResponse>> create(@RequestHeader("Idempotency-Key") UUID requestId,
                                                      @Valid @RequestBody CreateOrderRequest request) {
        return service.create(new CreateOrder(requestId, request.customerRef(), request.sku(), request.quantity()))
                .map(OrderResponse::from)
                .map(order -> ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order));
        // Le framework souscrit. Un rejeu identique retrouve la ressource et renvoie également 201, contrat documenté.
    }
    @GetMapping("/{id}")
    public Mono<OrderResponse> get(@PathVariable UUID id) { return service.get(id).map(OrderResponse::from); }

    @PostMapping(path="/{id}/cancel", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<OrderResponse> cancel(@PathVariable UUID id) { return service.cancel(id).map(OrderResponse::from); }

    @GetMapping
    public Mono<OrderPageResponse> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        return service.list(page, size).map(result -> new OrderPageResponse(
                result.items().stream().map(OrderResponse::from).toList(), result.page(), result.size(), result.hasNext()));
    }
    public record OrderPageResponse(List<OrderResponse> items, int page, int size, boolean hasNext) {}
}
