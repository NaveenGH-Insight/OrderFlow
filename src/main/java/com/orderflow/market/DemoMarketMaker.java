package com.orderflow.market;

import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import com.orderflow.engine.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DemoMarketMaker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoMarketMaker.class);
    private final OrderGateway orders;
    private final AtomicBoolean buyNext = new AtomicBoolean(true);

    public DemoMarketMaker(OrderGateway orders) { this.orders = orders; }

    @Scheduled(fixedRate = 1_500, initialDelay = 2_000)
    public void createDemoPrint() {
        OrderSide side = buyNext.getAndSet(!buyNext.get()) ? OrderSide.BUY : OrderSide.SELL;
        OrderRequest pulse = new OrderRequest("ORDF", "demo-flow", side, OrderType.MARKET, 0, 3);
        orders.place(pulse).whenComplete((result, error) -> {
            if (error != null) LOGGER.error("Demo market activity order failed", error);
        });
    }
}
