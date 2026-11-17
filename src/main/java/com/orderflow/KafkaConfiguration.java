package com.orderflow;

import com.orderflow.domain.Trade;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfiguration {
    @Bean
    @ConditionalOnProperty(name = "orderflow.kafka.enabled", havingValue = "true")
    KafkaTemplate<String, Trade> tradeKafkaTemplate(
            org.springframework.core.env.Environment environment) {
        String servers = environment.getProperty("spring.kafka.bootstrap-servers");
        if (servers == null || servers.isBlank()) {
            throw new IllegalStateException("Set spring.kafka.bootstrap-servers when orderflow.kafka.enabled=true");
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }
}
