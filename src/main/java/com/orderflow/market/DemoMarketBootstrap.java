package com.orderflow.market;

import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import com.orderflow.engine.OrderGateway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DemoMarketBootstrap implements ApplicationRunner {
    private final OrderGateway orders;
    private final MarketDataHub marketData;

    public DemoMarketBootstrap(OrderGateway orders, MarketDataHub marketData) {
        this.orders = orders;
        this.marketData = marketData;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        for (int level = 1; level <= 8; level++) {
            long quantity = 250;
            orders.place(new OrderRequest("ORDF", "market-maker", OrderSide.BUY, OrderType.LIMIT,
                    10_000 - level * 5L, quantity)).join();
            orders.place(new OrderRequest("ORDF", "market-maker", OrderSide.SELL, OrderType.LIMIT,
                    10_000 + level * 5L, quantity)).join();
        }
        marketData.publishNow();
    }
}
