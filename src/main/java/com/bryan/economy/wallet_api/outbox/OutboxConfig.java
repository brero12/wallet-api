package com.bryan.economy.wallet_api.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxConfig {

    /** 6 partitions, keyed by playerId, so one player's events stay ordered. */
    @Bean
    NewTopic transactionsTopic() {
        return TopicBuilder.name("economy.transactions").partitions(6).replicas(1).build();
    }
}