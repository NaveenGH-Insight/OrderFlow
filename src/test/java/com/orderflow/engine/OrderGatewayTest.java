package com.orderflow.engine;

import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderGatewayTest {
    @Test
    void publishesPlaceAndCancelCommandsThroughDisruptor() throws Exception {
        RiskLedger ledger = new RiskLedger();
        MatchingEngine engine = new MatchingEngine(ledger, ignored -> {});
        OrderGateway gateway = new OrderGateway(engine, ledger);
        gateway.start();
        try {
            OrderResponse placed = gateway.place(new OrderRequest("ORDF", "student",
                    OrderSide.BUY, OrderType.LIMIT, 9_990, 5)).get(5, TimeUnit.SECONDS);
            assertEquals("RESTING", placed.status());
            assertEquals(2_450_050, ledger.availableCash("student"));

            OrderResponse cancelled = gateway.cancel("student", placed.orderId()).get(5, TimeUnit.SECONDS);
            assertEquals("CANCELLED", cancelled.status());
            assertEquals(2_500_000, ledger.availableCash("student"));
        } finally {
            gateway.stop();
        }
    }
}
