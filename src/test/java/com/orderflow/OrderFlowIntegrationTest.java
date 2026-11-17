package com.orderflow;

import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderResponse;
import com.orderflow.market.DemoMarketMaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "orderflow.open-browser=false")
class OrderFlowIntegrationTest {
    @Autowired private WebTestClient client;
    @Autowired private DemoMarketMaker simulator;

    @Test
    void servesSeededMarketStreamsSnapshotsAndProcessesOrderLifecycle() {
        client.get().uri("/api/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.tradingMode").isEqualTo("SIMULATION_ONLY");

        MarketSnapshot firstSnapshot = client.get().uri("/api/market/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(MarketSnapshot.class)
                .getResponseBody()
                .next()
                .block(Duration.ofSeconds(3));
        assertNotNull(firstSnapshot);
        assertTrue(firstSnapshot.bestBidTicks() > 0);
        assertTrue(firstSnapshot.bestAskTicks() > firstSnapshot.bestBidTicks());

        OrderResponse placed = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"symbol":"ORDF","accountId":"student","side":"BUY","type":"LIMIT","priceTicks":1000,"quantity":1}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(placed);
        assertTrue(placed.remainingQuantity() > 0);

        client.post().uri("/api/orders/{orderId}/cancel?accountId=student", placed.orderId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void evolvingQuotesGenerateRealExecutionsAndExecutionDerivedCandles() {
        List<Long> prices = new ArrayList<>();
        for (int step = 0; step < 30; step++) {
            simulator.createDemoPrint();
            MarketSnapshot snapshot = client.get().uri("/api/market/snapshot").exchange()
                    .expectStatus().isOk().expectBody(MarketSnapshot.class).returnResult().getResponseBody();
            assertNotNull(snapshot);
            assertTrue(snapshot.bestAskTicks() > snapshot.bestBidTicks());
            assertTrue(snapshot.availableCashTicks() >= 0);
            assertEquals(snapshot.trades().get(0).priceTicks(), snapshot.lastPriceTicks());
            prices.add(snapshot.lastPriceTicks());
        }
        assertTrue(prices.stream().distinct().count() > 5, "Prices must evolve, not alternate between fixed bid/ask quotes");
        MarketSnapshot state = client.get().uri("/api/market/snapshot").exchange()
                .expectStatus().isOk().expectBody(MarketSnapshot.class).returnResult().getResponseBody();
        assertNotNull(state);
        assertTrue(state.candles().size() >= 60);
        assertTrue(state.candles().stream().map(candle -> candle.openTimeMillis()).distinct().count() == state.candles().size());

        long firstCoveredMinute = state.trades().stream().mapToLong(trade ->
                trade.timestamp().toEpochMilli() / 60_000 * 60_000).min().orElseThrow() + 60_000;
        var closedCandles = state.candles().stream()
                .filter(candle -> candle.openTimeMillis() >= firstCoveredMinute)
                .filter(candle -> candle.openTimeMillis() < state.candles().get(state.candles().size() - 1).openTimeMillis())
                .toList();
        // The retained tape covers complete historical minutes after its oldest, potentially partial bucket.
        for (var candle : closedCandles) {
            var executions = state.trades().stream()
                    .filter(trade -> trade.timestamp().toEpochMilli() / 60_000 * 60_000 == candle.openTimeMillis())
                    .sorted(Comparator.comparingLong(trade -> Long.parseLong(trade.id()))).toList();
            assertTrue(!executions.isEmpty());
            assertEquals(executions.getFirst().priceTicks(), candle.openTicks());
            assertEquals(executions.getLast().priceTicks(), candle.closeTicks());
            assertEquals(executions.stream().mapToLong(trade -> trade.priceTicks()).max().orElseThrow(), candle.highTicks());
            assertEquals(executions.stream().mapToLong(trade -> trade.priceTicks()).min().orElseThrow(), candle.lowTicks());
            assertEquals(executions.stream().mapToLong(trade -> trade.quantity()).sum(), candle.volume());
        }
        assertTrue(!closedCandles.isEmpty(), "Test must check at least one full execution-derived candle");
    }
}
