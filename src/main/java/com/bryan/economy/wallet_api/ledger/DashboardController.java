package com.bryan.economy.wallet_api.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class DashboardController {

    public record FlagRow(long id, String accountId, String rule, int score, String evidence, String time) {}
    public record EntryRow(long id, String type, String playerId, String accountType,
                           long amount, String region, String time) {}
    public record Invariants(long balanceSum, boolean balanced, long transactions,
                             long unpublishedOutbox, long flags) {}

    private final JdbcClient jdbc;

    public DashboardController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/fraud/flags")
    public List<FlagRow> flags(@RequestParam(defaultValue = "20") int limit) {
        return jdbc.sql("""
                SELECT id, account_id, rule, score, evidence::text AS evidence,
                       to_char(created_at AT TIME ZONE 'UTC', 'HH24:MI:SS') AS time
                FROM fraud_flags ORDER BY id DESC LIMIT :n""")
                .param("n", Math.min(limit, 100))
                .query((rs, i) -> new FlagRow(rs.getLong("id"), rs.getString("account_id"),
                        rs.getString("rule"), rs.getInt("score"), rs.getString("evidence"),
                        rs.getString("time")))
                .list();
    }

    /** Both legs of each transaction, newest first, so double-entry is visible. */
    @GetMapping("/ledger/recent")
    public List<EntryRow> recent(@RequestParam(defaultValue = "20") int limit) {
        return jdbc.sql("""
                SELECT e.id, t.type, a.player_id, a.type AS account_type, e.amount, t.region,
                       to_char(e.created_at AT TIME ZONE 'UTC', 'HH24:MI:SS') AS time
                FROM ledger_entries e
                JOIN transactions t ON t.id = e.txn_id
                JOIN accounts a ON a.id = e.account_id
                ORDER BY e.id DESC LIMIT :n""")
                .param("n", Math.min(limit, 100))
                .query((rs, i) -> new EntryRow(rs.getLong("id"), rs.getString("type"),
                        rs.getString("player_id"), rs.getString("account_type"),
                        rs.getLong("amount"), rs.getString("region"), rs.getString("time")))
                .list();
    }

    @GetMapping("/admin/invariants")
    public Invariants invariants() {
        long sum = jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts").query(Long.class).single();
        long txns = jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
        long pending = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class).single();
        long flags = jdbc.sql("SELECT COUNT(*) FROM fraud_flags").query(Long.class).single();
        return new Invariants(sum, sum == 0, txns, pending, flags);
    }
}