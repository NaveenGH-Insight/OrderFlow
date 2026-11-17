package com.orderflow.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orderflow.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class LocalTradeBroadcaster implements TradeBroadcaster {
    @Override
    public void publish(com.orderflow.domain.Trade trade) {
    }
}
