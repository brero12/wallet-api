package com.bryan.economy.wallet_api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String key, String topic, String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       @Value("${outbox.relay.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-ms:200}")
    public void tick() {
        try {
            tx.executeWithoutResult(status -> publishBatch());
        } catch (Exception e) {
            // transaction rolled back: rows stay unpublished and are retried on the next tick
            log.warn("Outbox publish failed, will retry: {}", e.getMessage());
        }
    }

    private void publishBatch() {
        List<Row> rows = jdbc.sql("""
                SELECT id, aggregate_id, topic, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED""")
                .param("limit", batchSize)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("aggregate_id"),
                        rs.getString("topic"), rs.getString("payload")))
                .list();
        if (rows.isEmpty()) return;

        List<CompletableFuture<?>> sends = new ArrayList<>();
        for (Row r : rows) {
            sends.add(kafka.send(r.topic(), r.key(), r.payload()));
        }
        try {
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka send failed", e);
        }

        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", rows.stream().map(Row::id).toList())
                .update();
        log.info("Published {} outbox events", rows.size());
    }
}