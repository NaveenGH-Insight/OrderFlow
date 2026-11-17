package com.orderflow.api;

import com.orderflow.domain.OrderResponse;
import com.orderflow.engine.OrderGateway;
import com.orderflow.market.MarketDataHub;
import com.orderflow.market.DesktopLauncher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(OrderController.class)
@Import(ApiExceptionHandler.class)
class OrderControllerTest {
    @Autowired private WebTestClient client;
    @MockBean private OrderGateway orderGateway;
    @MockBean private MarketDataHub marketDataHub;
    @MockBean private DesktopLauncher desktopLauncher;

    @Test
    void acceptsValidOrderAndReturnsStableJsonContract() {
        when(orderGateway.place(any())).thenReturn(CompletableFuture.completedFuture(
                new OrderResponse("order-1", "FILLED", 2, 0, 10_000, "Price-time priority applied.")));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"symbol":"ORDF","accountId":"student","side":"BUY","type":"LIMIT","priceTicks":10000,"quantity":2}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("order-1")
                .jsonPath("$.status").isEqualTo("FILLED")
                .jsonPath("$.filledQuantity").isEqualTo(2)
                .jsonPath("$.averagePriceTicks").isEqualTo(10_000);
    }

    @Test
    void rejectsMalformedOrderWithBadRequestProblem() {
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"symbol":"ORDF","accountId":"student","side":"BUY","type":"LIMIT","priceTicks":0,"quantity":0}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
