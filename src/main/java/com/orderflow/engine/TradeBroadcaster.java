package com.orderflow.engine;

import com.orderflow.domain.Trade;

@FunctionalInterface
public interface TradeBroadcaster {
    void publish(Trade trade);
}
