package com.bryan.economy.wallet_api.fraud;

import com.bryan.economy.wallet_api.ledger.Dtos.TxnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "fraud.enabled", havingValue = "true", matchIfMissing = true)
public class FraudConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudConsumer.class);

    private final ObjectMapper mapper;
    private final FraudRules rules;
    private final JdbcClient jdbc;

    public FraudConsumer(ObjectMapper mapper, FraudRules rules, JdbcClient jdbc) {
        this.mapper = mapper;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "economy.transactions")
    public void onMessage(String payload) {
        TxnEvent event;
        try {
            event = mapper.readValue(payload, TxnEvent.class);
        } catch (Exception ex) {
            log.error("Skipping unparseable event: {}", ex.getMessage());   // don't let one poison message block the partition
            return;
        }
        for (FraudFlag flag : rules.evaluate(event)) {
            save(flag);
        }
    }

    private void save(FraudFlag f) {
        int inserted = jdbc.sql("""
                INSERT INTO fraud_flags (account_id, rule, score, evidence, event_id)
                VALUES (:acct, :rule, :score, CAST(:ev AS jsonb), :eid)
                ON CONFLICT (event_id, rule) DO NOTHING""")
                .param("acct", f.accountId()).param("rule", f.rule()).param("score", f.score())
                .param("ev", mapper.writeValueAsString(f.evidence())).param("eid", f.eventId())
                .update();
        if (inserted == 1) {
            log.warn("FRAUD FLAG {} score={} account={} evidence={}",
                    f.rule(), f.score(), f.accountId(), f.evidence());
        }
    }
}