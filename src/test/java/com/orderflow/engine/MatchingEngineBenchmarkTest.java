package com.orderflow.engine;

import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchingEngineBenchmarkTest {
    private static final int ORDER_COUNT = 30_000;

    @Test
    void measuresMatchingCoreThroughputAndP99Latency() {
        MatchingEngine warmup = new MatchingEngine(new RiskLedger(), ignored -> {});
        for (int i = 0; i < 10_000; i++) {
            long price = 1_000 + (i % 8_000);
            warmup.place(Integer.toString(i),
                    new OrderRequest("ORDF", "student", OrderSide.BUY, OrderType.LIMIT, price, 1), price);
        }
        MatchingEngine engine = new MatchingEngine(new RiskLedger(), ignored -> {});
        long[] latencyNanos = new long[ORDER_COUNT];
        OrderRequest[] requests = new OrderRequest[ORDER_COUNT];
        String[] ids = new String[ORDER_COUNT];
        for (int i = 0; i < ORDER_COUNT; i++) {
            long price = 1_000 + (i % 8_000);
            requests[i] = new OrderRequest("ORDF", "student", OrderSide.BUY, OrderType.LIMIT, price, 1);
            ids[i] = Integer.toString(i);
        }
        long started = System.nanoTime();
        for (int i = 0; i < ORDER_COUNT; i++) {
            long orderStarted = System.nanoTime();
            OrderResponse result = engine.place(ids[i], requests[i], requests[i].priceTicks());
            latencyNanos[i] = System.nanoTime() - orderStarted;
            if (!result.status().equals("RESTING")) throw new AssertionError("Unexpected benchmark order result");
        }
        long elapsed = System.nanoTime() - started;
        Arrays.sort(latencyNanos);
        long ordersPerSecond = Math.round(ORDER_COUNT * 1_000_000_000d / elapsed);
        long p99Micros = latencyNanos[(int) Math.ceil(ORDER_COUNT * 0.99) - 1] / 1_000;
        System.out.printf("Matching core: %,d orders, %,d orders/sec, p99 %d us; excludes gateway, risk reservation, HTTP, Kafka and UI.%n",
                ORDER_COUNT, ordersPerSecond, p99Micros);
        assertEquals(ORDER_COUNT, engine.openOrderCount());
    }
}
