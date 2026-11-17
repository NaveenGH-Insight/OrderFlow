package com.orderflow.engine;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class OrderGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderGateway.class);
    private static final long MARKET_PROTECTION_BPS = 500;
    private final MatchingEngine engine;
    private final RiskLedger riskLedger;
    private final EngineMetrics metrics = new EngineMetrics();
    private Disruptor<OrderEvent> disruptor;
    private RingBuffer<OrderEvent> ring;

    public OrderGateway(MatchingEngine engine, RiskLedger riskLedger) {
        this.engine = engine;
        this.riskLedger = riskLedger;
    }

    @PostConstruct
    void start() {
        disruptor = new Disruptor<>(OrderEvent::new, 1024,
                Thread.ofPlatform().name("orderflow-matching-engine").factory(),
                ProducerType.MULTI, new BlockingWaitStrategy());
        EventHandler<OrderEvent> handler = (event, sequence, endOfBatch) -> {
            try {
                Object value = switch (event.action) {
                    case OrderEvent.PLACE -> engine.place(event.orderId, event.request, event.effectiveLimitTicks);
                    case OrderEvent.CANCEL -> engine.cancel(event.accountId, event.orderId);
                    case OrderEvent.REPLACE_QUOTES -> engine.replaceSimulationQuotes(event.quotes);
                    default -> throw new IllegalStateException("Unknown order event");
                };
                event.result.complete(value);
            } catch (Exception exception) {
                LOGGER.error("OrderFlow matching event failed for order {}", event.orderId, exception);
                event.result.completeExceptionally(exception);
            } finally {
                metrics.record(event.startedNanos);
                event.clear();
            }
        };
        disruptor.handleEventsWith(handler);
        ring = disruptor.start();
    }

    @PreDestroy
    void stop() {
        if (disruptor != null) disruptor.shutdown();
    }

    public CompletableFuture<OrderResponse> place(OrderRequest request) {
        validate(request);
        String id = UUID.randomUUID().toString();
        long effectiveLimit = request.priceTicks();
        if (request.type() == OrderType.MARKET) {
            long reference = engine.bestOpposingPrice(request.side());
            if (reference == 0) throw new IllegalArgumentException("No executable liquidity for this market order");
            effectiveLimit = request.side() == OrderSide.BUY
                    ? Math.addExact(reference, Math.max(1, reference * MARKET_PROTECTION_BPS / 10_000))
                    : Math.max(1, reference - Math.max(1, reference * MARKET_PROTECTION_BPS / 10_000));
        }
        riskLedger.reserve(request.accountId(), request.side(), effectiveLimit, request.quantity());
        CompletableFuture<Object> response;
        try {
            long started = System.nanoTime();
            response = publish(OrderEvent.PLACE, id, request.accountId(),
                    request, effectiveLimit, started);
        } catch (RuntimeException exception) {
            riskLedger.release(request.accountId(), request.side(), effectiveLimit, request.quantity());
            throw exception;
        }
        return response.thenApply(OrderResponse.class::cast);
    }

    public CompletableFuture<OrderResponse> cancel(String accountId, String orderId) {
        long started = System.nanoTime();
        return publish(OrderEvent.CANCEL, orderId, accountId, null, 0, started)
                .thenApply(OrderResponse.class::cast);
    }

    public CompletableFuture<Integer> replaceSimulationQuotes(List<OrderRequest> requests) {
        List<OrderRequest> quotes = List.copyOf(requests);
        for (OrderRequest quote : quotes) {
            validate(quote);
            if (!quote.accountId().equals("market-maker") || quote.type() != OrderType.LIMIT) {
                throw new IllegalArgumentException("Only limit quotes for the market-maker account may be refreshed.");
            }
        }
        CompletableFuture<Object> result = new CompletableFuture<>();
        long started = System.nanoTime();
        if (!ring.tryPublishEvent((event, sequence) -> {
            event.action = OrderEvent.REPLACE_QUOTES;
            event.quotes = quotes;
            event.startedNanos = started;
            event.result = result;
        })) throw new EngineBusyException("Matching engine queue is full; retry shortly.");
        return result.thenApply(Integer.class::cast);
    }

    public MarketSnapshot snapshot(String accountId) {
        MarketSnapshot state = engine.snapshot(accountId);
        return new MarketSnapshot(state.symbol(), state.sequence(), state.lastPriceTicks(),
                state.bestBidTicks(), state.bestAskTicks(), state.bids(), state.asks(),
                state.trades(), state.candles(), state.availableCashTicks(), state.availableShares(),
                metrics.ordersPerSecond(), metrics.p99Micros());
    }

    private CompletableFuture<Object> publish(int action, String id, String accountId,
                                               OrderRequest request, long limit, long started) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        if (!ring.tryPublishEvent((event, sequence, values) -> {
            event.action = (Integer) values[0];
            event.orderId = (String) values[1];
            event.accountId = (String) values[2];
            event.request = (OrderRequest) values[3];
            event.effectiveLimitTicks = (Long) values[4];
            event.startedNanos = (Long) values[5];
            event.result = result;
        }, action, id, accountId, request, limit, started)) {
            throw new EngineBusyException("Matching engine queue is full; retry shortly.");
        }
        return result;
    }

    private void validate(OrderRequest request) {
        if (request == null || request.symbol() == null || !request.symbol().equals("ORDF")) {
            throw new IllegalArgumentException("Only the ORDF demo instrument is available");
        }
        if (request.accountId() == null || !(request.accountId().equals("student")
                || request.accountId().equals("market-maker") || request.accountId().equals("demo-flow"))) {
            throw new IllegalArgumentException("Use one of the seeded educational demo accounts");
        }
        if (request.side() == null || request.type() == null || request.quantity() < 1 || request.quantity() > 100_000) {
            throw new IllegalArgumentException("Order side, type, or quantity is invalid");
        }
        if (request.type() == OrderType.LIMIT && (request.priceTicks() < 1 || request.priceTicks() > 100_000_000)) {
            throw new IllegalArgumentException("Limit price must be between 1 and 100000000 ticks");
        }
        if (request.type() == OrderType.MARKET && request.priceTicks() != 0) {
            throw new IllegalArgumentException("Market orders must not specify a limit price");
        }
    }

}
