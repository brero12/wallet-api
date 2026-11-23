package com.bryan.economy.wallet_api.ledger;

import com.bryan.economy.wallet_api.ledger.Dtos.*;
import com.bryan.economy.wallet_api.ledger.LedgerExceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"outbox.relay.enabled=false", "fraud.enabled=false"})
@Testcontainers
class LedgerConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;

    /** Runs all tasks at the same moment (start gate) and waits for them to finish. */
    private void runConcurrently(List<Runnable> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    t.run();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        }
    }

    private String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void grant(String player, long amount) {
        ledger.grant("grant-" + UUID.randomUUID(), new GrantRequest(player, amount, "fc26", "NA"));
    }

    @Test
    @Timeout(60)
    void concurrentPurchases_neverOverspend() throws Exception {
        String player = uniq("spender");
        grant(player, 100);                       // affords exactly 10 purchases of 10

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                try {
                    ledger.purchase("p-" + UUID.randomUUID(),
                            new PurchaseRequest(player, 10, "sword", "fc26", "NA"));
                    ok.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                }
            });
        }
        runConcurrently(tasks);

        assertEquals(10, ok.get(), "exactly 10 purchases should succeed");
        assertEquals(40, rejected.get(), "the other 40 should be rejected");
        assertEquals(0, ledger.balance(player).balance());
        assertBooksBalance();
    }

    @Test
    @Timeout(60)
    void sameIdempotencyKey_chargesOnce() throws Exception {
        String player = uniq("retry");
        grant(player, 100);

        String sharedKey = "same-key-" + UUID.randomUUID();
        AtomicInteger fresh = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                LedgerResult r = ledger.purchase(sharedKey,
                        new PurchaseRequest(player, 30, "shield", "fc26", "NA"));
                (r.replayed() ? replayed : fresh).incrementAndGet();
            });
        }
        runConcurrently(tasks);

        assertEquals(1, fresh.get(), "only one request should actually execute");
        assertEquals(19, replayed.get());
        assertEquals(70, ledger.balance(player).balance(), "charged exactly once");
        assertBooksBalance();
    }

    @Test
    @Timeout(60)
    void oppositeTransfers_doNotDeadlock() throws Exception {
        String a = uniq("a");
        String b = uniq("b");
        grant(a, 1000);
        grant(b, 1000);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            boolean forward = i % 2 == 0;
            String from = forward ? a : b;
            String to = forward ? b : a;
            tasks.add(() -> ledger.transfer("t-" + UUID.randomUUID(),
                    new TransferRequest(from, to, 1, "fc26", "NA")));
        }
        runConcurrently(tasks);

        assertEquals(1000, ledger.balance(a).balance());   // 50 out, 50 in
        assertEquals(1000, ledger.balance(b).balance());
        assertBooksBalance();
    }

    /** Double-entry invariant: all balances in the system sum to zero. */
    private void assertBooksBalance() {
        long total = jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts")
                .query(Long.class).single();
        assertEquals(0, total, "money was created or destroyed");
    }
}