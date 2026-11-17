package com.orderflow;

import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "orderflow.open-browser=false")
class OrderFlowIntegrationTest {
    @Autowired private WebTestClient client;

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
}
