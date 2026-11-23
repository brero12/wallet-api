package com.bryan.economy.wallet_api.fraud;

import com.bryan.economy.wallet_api.ledger.Dtos.TxnEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FraudRulesTest {

    static final long T = 1_700_000_000_000L;
    final FraudRules rules = new FraudRules();

    TxnEvent ev(String type, String player, String counterparty, long amount, String region, long ts) {
        return new TxnEvent(UUID.randomUUID().toString(), type, player, counterparty,
                amount, "GOLD", null, "fc26", region, Instant.ofEpochMilli(ts));
    }

    boolean has(List<FraudFlag> flags, String rule) {
        return flags.stream().anyMatch(f -> f.rule().equals(rule));
    }

    @Test
    void sixthPurchaseWithinAMinute_isFlagged() {
        for (int i = 0; i < 5; i++) {
            assertFalse(has(rules.evaluate(ev("PURCHASE", "p1", null, 1, "NA", T + i * 1000L)), "VELOCITY"));
        }
        assertTrue(has(rules.evaluate(ev("PURCHASE", "p1", null, 1, "NA", T + 5000)), "VELOCITY"));
    }

    @Test
    void slowPurchases_areNotFlagged() {
        for (int i = 0; i < 10; i++) {
            assertFalse(has(rules.evaluate(ev("PURCHASE", "p2", null, 1, "NA", T + i * 20_000L)), "VELOCITY"));
        }
    }

    @Test
    void quickRegionHop_isFlagged_slowOneIsNot() {
        rules.evaluate(ev("PURCHASE", "p3", null, 1, "NA", T));
        assertTrue(has(rules.evaluate(ev("PURCHASE", "p3", null, 1, "EU", T + 3000)), "IMPOSSIBLE_TRAVEL"));

        rules.evaluate(ev("PURCHASE", "p4", null, 1, "NA", T));
        assertFalse(has(rules.evaluate(ev("PURCHASE", "p4", null, 1, "EU", T + 60_000)), "IMPOSSIBLE_TRAVEL"));
    }

    @Test
    void grantThenDrain_isFlagged_smallTransferIsNot() {
        rules.evaluate(ev("GRANT", "m1", null, 1000, "NA", T));
        assertTrue(has(rules.evaluate(ev("TRANSFER", "m1", "m2", 950, "NA", T + 1000)), "MULE_DRAIN"));

        rules.evaluate(ev("GRANT", "m3", null, 1000, "NA", T));
        assertFalse(has(rules.evaluate(ev("TRANSFER", "m3", "m4", 100, "NA", T + 1000)), "MULE_DRAIN"));
    }

    @Test
    void redeliveredEvent_isIgnored() {
        TxnEvent e = ev("GRANT", "m5", null, 1000, "NA", T);
        rules.evaluate(e);
        assertTrue(rules.evaluate(e).isEmpty());
    }
}