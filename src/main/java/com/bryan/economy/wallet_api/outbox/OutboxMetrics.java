package com.bryan.economy.wallet_api.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, JdbcClient jdbc) {
        Gauge.builder("outbox.unpublished", () -> {
                    try {
                        return Double.valueOf(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                                .query(Long.class).single());
                    } catch (Exception e) {
                        return Double.NaN;
                    }
                })
                .description("Events waiting to be published to Kafka")
                .register(registry);
    }
}