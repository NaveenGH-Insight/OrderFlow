package com.orderflow.market;

import com.orderflow.domain.MarketSnapshot;
import com.orderflow.engine.OrderGateway;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class MarketDataHub {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataHub.class);
    private final OrderGateway gateway;
    private final Sinks.Many<MarketSnapshot> snapshots = Sinks.many().replay().latest();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orderflow-market-data");
        thread.setDaemon(true);
        return thread;
    });

    public MarketDataHub(OrderGateway gateway) { this.gateway = gateway; }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::publishSnapshot, 0,
                Duration.ofMillis(100).toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() { scheduler.shutdown(); }

    public Flux<MarketSnapshot> stream() { return snapshots.asFlux(); }
    public MarketSnapshot snapshot() { return gateway.snapshot("student"); }
    public void publishNow() { publishSnapshot(); }

    private void publishSnapshot() {
        try {
            Sinks.EmitResult result = snapshots.tryEmitNext(gateway.snapshot("student"));
            if (result.isFailure()) LOGGER.warn("Market snapshot was not broadcast: {}", result);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not create the next market data snapshot", exception);
        }
    }
}
