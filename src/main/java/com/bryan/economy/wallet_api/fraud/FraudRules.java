package com.bryan.economy.wallet_api.fraud;

import com.bryan.economy.wallet_api.ledger.Dtos.TxnEvent;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FraudRules {

    static final int VELOCITY_MAX = 5;
    static final long VELOCITY_WINDOW_MS = 60_000;
    static final long TRAVEL_WINDOW_MS = 10_000;
    static final long MULE_WINDOW_MS = 5 * 60_000;
    static final long MULE_MIN_AMOUNT = 500;

    private record Seen(String region, long ts) {}
    private record Inbound(long amount, long ts) {}

    private final Map<String, Deque<Long>> purchases = new HashMap<>();
    private final Map<String, Seen> lastSeen = new HashMap<>();
    private final Map<String, Inbound> inbound = new HashMap<>();

    /** Bounded memory of processed event ids, so redelivered events are ignored. */
    private final Set<String> processed = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 100_000;
                }
            });

    /** Synchronized on purpose: simple and safe for a demo. At scale, state is per partition. */
    public synchronized List<FraudFlag> evaluate(TxnEvent e) {
        if (!processed.add(e.eventId())) return List.of();

        List<FraudFlag> flags = new ArrayList<>();
        long ts = e.occurredAt().toEpochMilli();

        // Rule 1: velocity (sliding window of purchase timestamps per player)
        if ("PURCHASE".equals(e.type())) {
            Deque<Long> q = purchases.computeIfAbsent(e.playerId(), k -> new ArrayDeque<>());
            q.addLast(ts);
            while (q.peekFirst() < ts - VELOCITY_WINDOW_MS) q.pollFirst();
            if (q.size() > VELOCITY_MAX) {
                flags.add(new FraudFlag(e.playerId(), "VELOCITY",
                        Math.min(100, 50 + 10 * (q.size() - VELOCITY_MAX)), e.eventId(),
                        Map.of("purchasesInWindow", q.size(), "windowSeconds", VELOCITY_WINDOW_MS / 1000)));
            }
        }

        // Rule 2: impossible travel (region changed too fast)
        Seen prev = lastSeen.put(e.playerId(), new Seen(e.region(), ts));
        if (prev != null && !prev.region().equals(e.region())
                && Math.abs(ts - prev.ts()) < TRAVEL_WINDOW_MS) {
            flags.add(new FraudFlag(e.playerId(), "IMPOSSIBLE_TRAVEL", 80, e.eventId(),
                    Map.of("fromRegion", prev.region(), "toRegion", e.region(),
                            "gapMs", Math.abs(ts - prev.ts()))));
        }

        // Rule 3: mule drain (money in, then almost all of it straight back out)
        if ("TRANSFER".equals(e.type())) {
            Inbound in = inbound.get(e.playerId());
            if (in != null && in.amount() >= MULE_MIN_AMOUNT
                    && ts - in.ts() <= MULE_WINDOW_MS
                    && e.amount() >= in.amount() * 0.9) {
                flags.add(new FraudFlag(e.playerId(), "MULE_DRAIN", 90, e.eventId(),
                        Map.of("received", in.amount(), "sent", e.amount(),
                                "sentTo", String.valueOf(e.counterpartyId()))));
                inbound.remove(e.playerId());
            }
            if (e.counterpartyId() != null) recordInbound(e.counterpartyId(), e.amount(), ts);
        } else if ("GRANT".equals(e.type())) {
            recordInbound(e.playerId(), e.amount(), ts);
        }

        return flags;
    }

    private void recordInbound(String player, long amount, long ts) {
        inbound.merge(player, new Inbound(amount, ts),
                (old, fresh) -> ts - old.ts() <= MULE_WINDOW_MS
                        ? new Inbound(old.amount() + amount, ts)
                        : fresh);
    }
}