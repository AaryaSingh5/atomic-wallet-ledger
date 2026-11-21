package com.example.wallet;

import com.example.wallet.api.ApiException;
import com.example.wallet.api.Dtos.RegisterRequest;
import com.example.wallet.api.Dtos.RegisterResponse;
import com.example.wallet.repo.AccountRepository;
import com.example.wallet.repo.LedgerEntryRepository;
import com.example.wallet.service.AuthService;
import com.example.wallet.service.ReconciliationService;
import com.example.wallet.service.ReconciliationService.ReconciliationReport;
import com.example.wallet.service.WalletService;
import com.example.wallet.service.WalletService.TransactionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against in-memory H2 (PostgreSQL mode), so no Docker is needed. All tests share one
 * database; every test ends by asserting the GLOBAL ledger is still balanced.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletLedgerIntegrationTest {

    @Autowired AuthService auth;
    @Autowired WalletService wallet;
    @Autowired ReconciliationService reconciliation;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository entries;

    // ---------------------------------------------------------------------------------------

    @Test
    void topUpAndTransferMoveMoneyWithBalancedEntries() {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");

        wallet.topUp(alice.userId(), key(), 10_000, "initial");
        TransactionResult transfer = wallet.transfer(alice.userId(), key(), bob.email(), 2_500, "lunch");

        assertThat(balanceOf(alice.userId())).isEqualTo(7_500);
        assertThat(balanceOf(bob.userId())).isEqualTo(2_500);
        assertThat(transfer.replayed()).isFalse();

        // exactly one debit and one credit for the transfer
        var transferEntries = entries.findAll().stream()
                .filter(e -> e.getTransactionId().equals(transfer.transaction().getId()))
                .toList();
        assertThat(transferEntries).hasSize(2);
        assertThat(transferEntries.stream().mapToLong(e -> e.getAmount()).distinct().count()).isEqualTo(1);

        assertBooksBalance();
    }

    @Test
    void insufficientFundsRollsBackCompletely() {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 1_000, null);
        long entriesBefore = entries.count();

        assertThatThrownBy(() -> wallet.transfer(alice.userId(), key(), bob.email(), 1_001, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(alice.userId())).isEqualTo(1_000);
        assertThat(balanceOf(bob.userId())).isZero();
        assertThat(entries.count()).isEqualTo(entriesBefore);   // no partial writes
        assertBooksBalance();
    }

    @Test
    void sameIdempotencyKeyIsAppliedOnce() {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 5_000, null);

        String k = key();
        TransactionResult first = wallet.transfer(alice.userId(), k, bob.email(), 1_000, null);
        TransactionResult retry = wallet.transfer(alice.userId(), k, bob.email(), 1_000, null);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(balanceOf(alice.userId())).isEqualTo(4_000);   // debited once, not twice
        assertThat(balanceOf(bob.userId())).isEqualTo(1_000);
        assertBooksBalance();
    }

    @Test
    void reusingKeyWithDifferentAmountIsRejected() {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 5_000, null);

        String k = key();
        wallet.transfer(alice.userId(), k, bob.email(), 1_000, null);

        assertThatThrownBy(() -> wallet.transfer(alice.userId(), k, bob.email(), 2_000, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balanceOf(alice.userId())).isEqualTo(4_000);
        assertBooksBalance();
    }

    @Test
    void cannotTransferToSelf() {
        RegisterResponse alice = newUser("alice");
        wallet.topUp(alice.userId(), key(), 1_000, null);

        assertThatThrownBy(() -> wallet.transfer(alice.userId(), key(), alice.email(), 100, null))
                .isInstanceOf(ApiException.class);
        assertThat(balanceOf(alice.userId())).isEqualTo(1_000);
    }

    // ---------------------------- concurrency ----------------------------------------------

    /** 100 parallel transfers of 300 from a wallet holding 10,000: exactly 33 may succeed. */
    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 10_000, null);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(100, i -> {
            try {
                wallet.transfer(alice.userId(), key(), bob.email(), 300, null);
                succeeded.incrementAndGet();
            } catch (ApiException e) {
                if ("INSUFFICIENT_FUNDS".equals(e.getCode())) {
                    rejected.incrementAndGet();
                } else {
                    throw e;
                }
            }
        });

        assertThat(succeeded.get()).isEqualTo(33);
        assertThat(rejected.get()).isEqualTo(67);
        assertThat(balanceOf(alice.userId())).isEqualTo(100);
        assertThat(balanceOf(bob.userId())).isEqualTo(9_900);
        assertBooksBalance();
    }

    /** A->B and B->A at the same time would deadlock without ordered locking. */
    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 10_000, null);
        wallet.topUp(bob.userId(), key(), 10_000, null);

        runConcurrently(100, i -> {
            if (i % 2 == 0) {
                wallet.transfer(alice.userId(), key(), bob.email(), 10, null);
            } else {
                wallet.transfer(bob.userId(), key(), alice.email(), 10, null);
            }
        });

        assertThat(balanceOf(alice.userId())).isEqualTo(10_000);   // 50 out, 50 in
        assertThat(balanceOf(bob.userId())).isEqualTo(10_000);
        assertBooksBalance();
    }

    /** Ten simultaneous retries of the SAME request must produce exactly one transaction. */
    @Test
    void concurrentRetriesWithSameKeyApplyOnce() throws Exception {
        RegisterResponse alice = newUser("alice");
        RegisterResponse bob = newUser("bob");
        wallet.topUp(alice.userId(), key(), 10_000, null);

        String k = key();
        Set<UUID> transactionIds = ConcurrentHashMap.newKeySet();
        AtomicInteger replays = new AtomicInteger();

        runConcurrently(10, i -> {
            TransactionResult result = wallet.transfer(alice.userId(), k, bob.email(), 500, null);
            transactionIds.add(result.transaction().getId());
            if (result.replayed()) {
                replays.incrementAndGet();
            }
        });

        assertThat(transactionIds).hasSize(1);
        assertThat(replays.get()).isEqualTo(9);
        assertThat(balanceOf(alice.userId())).isEqualTo(9_500);
        assertThat(balanceOf(bob.userId())).isEqualTo(500);
        assertBooksBalance();
    }

    // ---------------------------- helpers --------------------------------------------------

    private RegisterResponse newUser(String prefix) {
        return auth.register(new RegisterRequest(prefix,
                prefix + "-" + UUID.randomUUID() + "@test.com", "password123"));
    }

    private long balanceOf(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow().getBalance();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private void assertBooksBalance() {
        ReconciliationReport report = reconciliation.run();
        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.unbalancedTransactions()).isEmpty();
        assertThat(report.sumOfAllBalances()).isZero();
        assertThat(report.totalDebits()).isEqualTo(report.totalCredits());
        assertThat(report.balanced()).isTrue();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index) throws Exception;
    }

    /** Releases all tasks at once so they genuinely contend. Fails if any task throws. */
    private void runConcurrently(int tasks, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
