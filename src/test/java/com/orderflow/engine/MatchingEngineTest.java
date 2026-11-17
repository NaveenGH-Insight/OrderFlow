package com.orderflow.engine;

import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatchingEngineTest {
    private RiskLedger ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new RiskLedger();
        ledger.fund("buyer", 1_000_000, 10);
        ledger.fund("seller-a", 0, 100);
        ledger.fund("seller-b", 0, 100);
        engine = new MatchingEngine(ledger, ignored -> {});
    }

    @Test
    void matchesBestPriceBeforeOlderWorsePricedOrder() {
        sell("older-ask", "seller-a", 10_020, 1);
        sell("better-ask", "seller-b", 10_010, 1);
        ledger.reserve("buyer", OrderSide.BUY, 10_020, 1);

        OrderResponse result = engine.place("buyer-order",
                request("buyer", OrderSide.BUY, OrderType.LIMIT, 10_020, 1), 10_020);

        assertEquals("FILLED", result.status());
        assertEquals(10_010, result.averagePriceTicks());
        assertEquals(989_990, ledger.availableCash("buyer"));
        assertEquals(11, ledger.availableShares("buyer"));
        assertEquals(10_010, ledger.availableCash("seller-b"));
        assertEquals(10_020, engine.snapshot("buyer").bestAskTicks());
    }

    @Test
    void preservesArrivalOrderWithinTheSamePriceLevel() {
        sell("first-ask", "seller-a", 10_000, 1);
        sell("second-ask", "seller-b", 10_000, 1);
        ledger.reserve("buyer", OrderSide.BUY, 10_000, 1);

        engine.place("buyer-order", request("buyer", OrderSide.BUY, OrderType.LIMIT, 10_000, 1), 10_000);

        assertEquals(10_000, ledger.availableCash("seller-a"));
        assertEquals(0, ledger.availableCash("seller-b"));
        assertEquals(10_000, engine.snapshot("buyer").bestAskTicks());
    }

    @Test
    void executesMarketQuantityAndReleasesUnfilledProtectedFunds() {
        sell("ask", "seller-a", 10_000, 1);
        OrderRequest request = request("buyer", OrderSide.BUY, OrderType.MARKET, 0, 2);
        ledger.reserve("buyer", OrderSide.BUY, 10_500, 2);

        OrderResponse result = engine.place("market-buy", request, 10_500);

        assertEquals("PARTIALLY_FILLED", result.status());
        assertEquals(1, result.filledQuantity());
        assertEquals(1, result.remainingQuantity());
        assertEquals(990_000, ledger.availableCash("buyer"));
    }

    @Test
    void cancellationReleasesTheRemainingReservation() {
        ledger.reserve("buyer", OrderSide.BUY, 9_990, 10);
        engine.place("resting-bid",
                request("buyer", OrderSide.BUY, OrderType.LIMIT, 9_990, 10), 9_990);

        OrderResponse result = engine.cancel("buyer", "resting-bid");

        assertEquals("CANCELLED", result.status());
        assertEquals(1_000_000, ledger.availableCash("buyer"));
    }

    @Test
    void riskLedgerRejectsOrdersBeyondAvailableBuyingPower() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger.reserve("buyer", OrderSide.BUY, 100_001, 10));
        assertEquals(1_000_000, ledger.availableCash("buyer"));
    }

    @Test
    void preventsSelfTradeAndReleasesUnfilledRisk() {
        ledger.reserve("buyer", OrderSide.SELL, 9_990, 1);
        engine.place("own-ask", request("buyer", OrderSide.SELL, OrderType.LIMIT, 9_990, 1), 9_990);
        ledger.reserve("buyer", OrderSide.BUY, 10_000, 1);

        OrderResponse result = engine.place("own-buy",
                request("buyer", OrderSide.BUY, OrderType.LIMIT, 10_000, 1), 10_000);

        assertEquals("SELF_TRADE_PREVENTED", result.status());
        assertEquals(1_000_000, ledger.availableCash("buyer"));
        assertEquals(9, ledger.availableShares("buyer"));
    }

    @Test
    void refreshingOneAccountsQuotesReleasesOnlyItsReservations() {
        sell("maker-ask", "seller-a", 10_000, 4);
        sell("other-ask", "seller-b", 10_100, 2);
        ledger.reserve("buyer", OrderSide.BUY, 9_990, 3);
        engine.place("user-bid", request("buyer", OrderSide.BUY, OrderType.LIMIT, 9_990, 3), 9_990);

        assertEquals(1, engine.cancelAccountOrders("seller-a"));
        assertEquals(100, ledger.availableShares("seller-a"));
        assertEquals(98, ledger.availableShares("seller-b"));
        assertEquals(2, engine.openOrderCount());
        assertEquals(10_100, engine.snapshot("buyer").bestAskTicks());
        assertEquals(970_030, ledger.availableCash("buyer"));
    }

    private void sell(String id, String account, long price, long quantity) {
        ledger.reserve(account, OrderSide.SELL, price, quantity);
        engine.place(id, request(account, OrderSide.SELL, OrderType.LIMIT, price, quantity), price);
    }

    private static OrderRequest request(String account, OrderSide side, OrderType type, long price, long quantity) {
        return new OrderRequest("ORDF", account, side, type, price, quantity);
    }
}
