package com.orderflow.market;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DemoMarketBootstrap implements ApplicationRunner {
    private final DemoMarketMaker simulator;
    private final MarketDataHub marketData;

    public DemoMarketBootstrap(DemoMarketMaker simulator, MarketDataHub marketData) {
        this.simulator = simulator;
        this.marketData = marketData;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        simulator.seedHistory();
        marketData.publishNow();
    }
}
