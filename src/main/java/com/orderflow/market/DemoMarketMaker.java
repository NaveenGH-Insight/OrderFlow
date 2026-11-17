package com.orderflow.market;

import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import com.orderflow.engine.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.ArrayList;

@Component
public class DemoMarketMaker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoMarketMaker.class);
    private final OrderGateway orders;
    private final MarketClock clock;
    private final Random random = new Random();
    private volatile boolean ready;
    private double fairPrice = 18_640;
    private double momentum;

    public DemoMarketMaker(OrderGateway orders, MarketClock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public synchronized void seedHistory() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(60, ChronoUnit.MINUTES);
        try {
            for (int step = 0; step < 720; step++) {
                clock.replayAt(start.plusSeconds(step * 5L));
                tick();
            }
        } finally {
            clock.useWallClock();
        }
        ready = true;
    }

    @Scheduled(fixedDelay = 650, initialDelay = 2_000)
    public synchronized void createDemoPrint() {
        if (!ready) return;
        try {
            tick();
        } catch (RuntimeException exception) {
            LOGGER.error("Simulated market cycle failed; the next scheduled cycle will retry", exception);
        }
    }

    void tick() {
        // Persistent order-flow momentum gives trends and reversals without pinning quotes to the opening price.
        momentum = momentum * 0.88 + random.nextGaussian() * 1.8;
        fairPrice = Math.max(2_000, Math.min(40_000, fairPrice + momentum + random.nextGaussian() * 4));
        long midpoint = Math.round(fairPrice);
        long halfSpread = 2 + random.nextInt(4);
        var quotes = new ArrayList<OrderRequest>(14);
        for (int level = 0; level < 7; level++) {
            long distance = halfSpread + level * 4L;
            long quantity = 65 + random.nextInt(240);
            quotes.add(new OrderRequest("ORDF", "market-maker", OrderSide.BUY,
                    OrderType.LIMIT, midpoint - distance, quantity));
            quotes.add(new OrderRequest("ORDF", "market-maker", OrderSide.SELL,
                    OrderType.LIMIT, midpoint + distance, 65 + random.nextInt(240)));
        }
        orders.replaceSimulationQuotes(quotes).join();

        MarketSnapshot liquidity = orders.snapshot("demo-flow");
        long quantity = 3 + random.nextInt(23);
        boolean buy = random.nextDouble() < Math.max(0.25, Math.min(0.75, 0.5 + momentum / 25));
        if (liquidity.availableCashTicks() < liquidity.bestAskTicks() * quantity * 2) buy = false;
        if (liquidity.availableShares() < quantity * 2) buy = true;
        OrderSide side = buy ? OrderSide.BUY : OrderSide.SELL;
        orders.place(new OrderRequest("ORDF", "demo-flow", side, OrderType.MARKET, 0, quantity)).join();
    }
}
