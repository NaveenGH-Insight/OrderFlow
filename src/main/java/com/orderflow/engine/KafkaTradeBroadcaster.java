package com.orderflow.engine;

import com.orderflow.domain.Trade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orderflow.kafka.enabled", havingValue = "true")
public class KafkaTradeBroadcaster implements TradeBroadcaster {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaTradeBroadcaster.class);
    private final KafkaTemplate<String, Trade> kafkaTemplate;
    private final String topic;

    public KafkaTradeBroadcaster(KafkaTemplate<String, Trade> kafkaTemplate,
                                 org.springframework.core.env.Environment environment) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = environment.getProperty("orderflow.kafka.topic", "orderflow.trades");
    }

    @Override
    public void publish(Trade trade) {
        try {
            kafkaTemplate.send(topic, trade.symbol(), trade).whenComplete((result, error) -> {
                if (error != null) LOGGER.error("Kafka broadcast failed for trade {} to topic {}",
                        trade.id(), topic, error);
            });
        } catch (RuntimeException exception) {
            LOGGER.error("Could not enqueue trade {} to Kafka topic {}", trade.id(), topic, exception);
        }
    }
}
