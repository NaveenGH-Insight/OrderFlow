package com.orderflow.api;

import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.engine.OrderGateway;
import com.orderflow.market.DesktopLauncher;
import com.orderflow.market.MarketDataHub;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderGateway orders;
    private final MarketDataHub marketData;
    private final DesktopLauncher desktopLauncher;

    public OrderController(OrderGateway orders, MarketDataHub marketData, DesktopLauncher desktopLauncher) {
        this.orders = orders;
        this.marketData = marketData;
        this.desktopLauncher = desktopLauncher;
    }

    @PostMapping("/orders")
    public Mono<OrderResponse> place(@Valid @RequestBody OrderRequest request) {
        return Mono.fromFuture(orders.place(request));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Mono<OrderResponse> cancel(@PathVariable("orderId") String orderId,
                                      @RequestParam("accountId") String accountId) {
        return Mono.fromFuture(orders.cancel(accountId, orderId));
    }

    @GetMapping("/market/snapshot")
    public MarketSnapshot snapshot() { return marketData.snapshot(); }

    @GetMapping(value = "/market/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<MarketSnapshot> stream() { return marketData.stream(); }

    @GetMapping("/health")
    public HealthResponse health() { return new HealthResponse("UP", "SIMULATION_ONLY", "OrderFlow"); }

    @PostMapping("/shutdown")
    public Mono<Void> shutdown(@RequestHeader("X-OrderFlow-Action") String action,
                               ServerWebExchange exchange) {
        if (!action.equals("quit")) return Mono.error(new IllegalArgumentException("Invalid shutdown action"));
        return exchange.getResponse().setComplete().then(Mono.fromRunnable(() ->
                CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS)
                        .execute(desktopLauncher::stopApplication)));
    }

    public record HealthResponse(String status, String tradingMode, String application) {}
}
