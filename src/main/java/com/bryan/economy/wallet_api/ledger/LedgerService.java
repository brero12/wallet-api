package com.bryan.economy.wallet_api.ledger;

import tools.jackson.databind.ObjectMapper;
import com.bryan.economy.wallet_api.ledger.Dtos.*;
import com.bryan.economy.wallet_api.ledger.LedgerExceptions.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    static final String CURRENCY = "GOLD";
    static final String TOPIC = "economy.transactions";
    private static final int SHARDS = 16;
    static final UUID[] FAUCETS = shards("00000000-0000-0000-0000-000000000001", "0002");
    static final UUID[] SINKS   = shards("00000000-0000-0000-0000-000000000002", "0001");

    private record Locked(long balance, boolean allowNegative) {}
    private record Existing(UUID id, String requestHash, String type) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public LedgerService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---------- Public operations ----------

    @Transactional
    public LedgerResult grant(String key, GrantRequest r) {
        UUID txnId = UUID.randomUUID();
        String hash = hash("GRANT", r.playerId(), r.amount(), r.gameId(), r.region());
        Optional<LedgerResult> replay = begin(txnId, key, hash, "GRANT", r.gameId(), r.region(), "{}");
        if (replay.isPresent()) return replay.get();

        UUID wallet = walletId(r.playerId());
        move(txnId, pick(FAUCETS, txnId), wallet, r.amount());
        publish(new TxnEvent(txnId.toString(), "GRANT", r.playerId(), null,
                r.amount(), CURRENCY, null, r.gameId(), r.region(), Instant.now()));
        return new LedgerResult(txnId, "GRANT", false);
    }

    @Transactional
    public LedgerResult purchase(String key, PurchaseRequest r) {
        UUID txnId = UUID.randomUUID();
        String hash = hash("PURCHASE", r.playerId(), r.amount(), r.itemId(), r.gameId(), r.region());
        Optional<LedgerResult> replay = begin(txnId, key, hash, "PURCHASE", r.gameId(), r.region(),
                json(Map.of("itemId", r.itemId())));
        if (replay.isPresent()) return replay.get();

        UUID wallet = walletId(r.playerId());
        move(txnId, wallet, pick(SINKS, txnId), r.amount());
        publish(new TxnEvent(txnId.toString(), "PURCHASE", r.playerId(), null,
                r.amount(), CURRENCY, r.itemId(), r.gameId(), r.region(), Instant.now()));
        return new LedgerResult(txnId, "PURCHASE", false);
    }

    @Transactional
    public LedgerResult transfer(String key, TransferRequest r) {
        if (r.fromPlayerId().equals(r.toPlayerId())) {
            throw new BadRequestException("Cannot transfer to yourself");
        }
        UUID txnId = UUID.randomUUID();
        String hash = hash("TRANSFER", r.fromPlayerId(), r.toPlayerId(), r.amount(), r.gameId(), r.region());
        Optional<LedgerResult> replay = begin(txnId, key, hash, "TRANSFER", r.gameId(), r.region(), "{}");
        if (replay.isPresent()) return replay.get();

        // create wallets in a consistent order so concurrent first-time transfers can't deadlock
        UUID from, to;
        if (r.fromPlayerId().compareTo(r.toPlayerId()) < 0) {
            from = walletId(r.fromPlayerId());
            to = walletId(r.toPlayerId());
        } else {
            to = walletId(r.toPlayerId());
            from = walletId(r.fromPlayerId());
        }
        move(txnId, from, to, r.amount());
        publish(new TxnEvent(txnId.toString(), "TRANSFER", r.fromPlayerId(), r.toPlayerId(),
                r.amount(), CURRENCY, null, r.gameId(), r.region(), Instant.now()));
        return new LedgerResult(txnId, "TRANSFER", false);
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(String playerId) {
        long bal = jdbc.sql("""
                SELECT balance FROM accounts
                WHERE player_id = :p AND currency = :c AND type = 'PLAYER_WALLET'""")
                .param("p", playerId).param("c", CURRENCY)
                .query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Unknown player: " + playerId));
        return new BalanceResponse(playerId, bal);
    }

    // ---------- Internals ----------

    /**
     * Claims the idempotency key. Returns empty if this is a fresh request (proceed),
     * or the original result if it's a replay. If another request with the same key is
     * still in flight, the INSERT blocks until that transaction commits or rolls back.
     */
    private Optional<LedgerResult> begin(UUID txnId, String key, String hash, String type,
                                         String gameId, String region, String metadataJson) {
        int inserted = jdbc.sql("""
                INSERT INTO transactions (id, idempotency_key, request_hash, type, game_id, region, metadata)
                VALUES (:id, :key, :hash, :type, :game, :region, CAST(:meta AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("id", txnId).param("key", key).param("hash", hash).param("type", type)
                .param("game", gameId).param("region", region).param("meta", metadataJson)
                .update();
        if (inserted == 1) return Optional.empty();

        Existing existing = jdbc.sql("""
                SELECT id, request_hash, type FROM transactions WHERE idempotency_key = :key""")
                .param("key", key)
                .query((rs, n) -> new Existing(
                        rs.getObject("id", UUID.class), rs.getString("request_hash"), rs.getString("type")))
                .single();

        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyConflictException("Idempotency-Key was already used with a different request");
        }
        return Optional.of(new LedgerResult(existing.id(), existing.type(), true));
    }

    private UUID walletId(String playerId) {
        jdbc.sql("""
                INSERT INTO accounts (id, player_id, currency, type)
                VALUES (:id, :p, :c, 'PLAYER_WALLET')
                ON CONFLICT (player_id, currency, type) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("p", playerId).param("c", CURRENCY)
                .update();
        return jdbc.sql("""
                SELECT id FROM accounts
                WHERE player_id = :p AND currency = :c AND type = 'PLAYER_WALLET'""")
                .param("p", playerId).param("c", CURRENCY)
                .query(UUID.class).single();
    }

    /** Moves `amount` from one account to another: lock, check, update balances, write ledger rows. */
    private void move(UUID txnId, UUID from, UUID to, long amount) {
        Map<UUID, Locked> locked = lockInOrder(List.of(from, to));

        Locked src = locked.get(from);
        if (!src.allowNegative() && src.balance() < amount) {
            throw new InsufficientFundsException(
                    "Insufficient funds: balance " + src.balance() + ", required " + amount);
        }

        applyDelta(from, -amount);
        applyDelta(to, amount);
        insertEntry(txnId, from, -amount);
        insertEntry(txnId, to, amount);
    }

    /** Always lock rows in ascending id order so two transactions can never deadlock each other. */
    private Map<UUID, Locked> lockInOrder(Collection<UUID> ids) {
        List<UUID> sorted = ids.stream().distinct().sorted().toList();
        Map<UUID, Locked> result = new LinkedHashMap<>();
        jdbc.sql("SELECT id, balance, allow_negative FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", sorted)
                .query((rs, n) -> {
                    result.put(rs.getObject("id", UUID.class),
                            new Locked(rs.getLong("balance"), rs.getBoolean("allow_negative")));
                    return null;
                }).list();
        return result;
    }

    private void applyDelta(UUID accountId, long delta) {
        jdbc.sql("UPDATE accounts SET balance = balance + :d, version = version + 1 WHERE id = :id")
                .param("d", delta).param("id", accountId).update();
    }

    private void insertEntry(UUID txnId, UUID accountId, long amount) {
        jdbc.sql("INSERT INTO ledger_entries (txn_id, account_id, amount) VALUES (:t, :a, :amt)")
                .param("t", txnId).param("a", accountId).param("amt", amount).update();
    }

    private void publish(TxnEvent event) {
        jdbc.sql("""
                INSERT INTO outbox (aggregate_id, topic, payload)
                VALUES (:agg, :topic, CAST(:payload AS jsonb))""")
                .param("agg", event.playerId()).param("topic", TOPIC).param("payload", json(event))
                .update();
    }

    private String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    private static String hash(Object... parts) {
        try {
            String joined = Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining("|"));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID[] shards(String first, String group) {
        UUID[] a = new UUID[SHARDS];
        a[0] = UUID.fromString(first);
        for (int i = 1; i < SHARDS; i++) {
            a[i] = UUID.fromString("00000000-0000-0000-%s-%012x".formatted(group, i));
        }
        return a;
    }

    private static UUID pick(UUID[] shards, UUID txnId) {
        return shards[Math.floorMod(txnId.hashCode(), shards.length)];
    }
}