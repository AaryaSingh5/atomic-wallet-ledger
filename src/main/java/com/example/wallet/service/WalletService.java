package com.example.wallet.service;

import com.example.wallet.api.ApiException;
import com.example.wallet.domain.Account;
import com.example.wallet.domain.EntryDirection;
import com.example.wallet.domain.LedgerEntry;
import com.example.wallet.domain.OutboxEvent;
import com.example.wallet.domain.TransactionType;
import com.example.wallet.domain.User;
import com.example.wallet.domain.WalletTransaction;
import com.example.wallet.repo.AccountRepository;
import com.example.wallet.repo.LedgerEntryRepository;
import com.example.wallet.repo.OutboxEventRepository;
import com.example.wallet.repo.UserRepository;
import com.example.wallet.repo.WalletTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every money movement is a double-entry posting: one DEBIT on the source
 * account and one CREDIT
 * on the destination, for the same amount, written in ONE database transaction
 * together with the
 * balance updates. Either everything commits or nothing does.
 *
 * Correctness rests on three mechanisms:
 * 1. Row locks (SELECT ... FOR UPDATE) on both accounts, always taken in
 * ascending id order,
 * so concurrent transfers cannot overdraw or deadlock.
 * 2. An idempotency key (unique per user) so a retried request is applied
 * exactly once.
 * 3. DB constraints (balance >= 0, unique key, amount > 0) as a final safety
 * net.
 */
@Service
public class WalletService {

    /**
     * Result of a money movement. {@code replayed} is true when an earlier result
     * was returned.
     */
    public record TransactionResult(WalletTransaction transaction, boolean replayed) {
    }

    private final AccountRepository accounts;
    private final UserRepository users;
    private final WalletTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final OutboxEventRepository outboxEvents;

    public WalletService(AccountRepository accounts, UserRepository users,
            WalletTransactionRepository transactions, LedgerEntryRepository entries,
            OutboxEventRepository outboxEvents) {
        this.accounts = accounts;
        this.users = users;
        this.transactions = transactions;
        this.entries = entries;
        this.outboxEvents = outboxEvents;
    }

    /**
     * Adds money to the caller's wallet. In a real system this is called after the
     * payment
     * gateway confirms a payment; here the counter-party is the platform funding
     * account.
     */
    @Transactional
    public TransactionResult topUp(UUID userId, String idempotencyKey, long amount, String note) {
        UUID walletId = walletIdOf(userId);
        return execute(userId, idempotencyKey, TransactionType.TOPUP,
                Account.SYSTEM_FUNDING_ID, walletId, amount, note);
    }

    @Transactional
    public TransactionResult transfer(UUID senderId, String idempotencyKey, String receiverEmail,
            long amount, String note) {
        User receiver = users.findByEmail(AuthService.normalize(receiverEmail))
                .orElseThrow(() -> ApiException.notFound("Receiver not found"));
        if (receiver.getId().equals(senderId)) {
            throw ApiException.badRequest("Cannot transfer to yourself");
        }
        return execute(senderId, idempotencyKey, TransactionType.TRANSFER,
                walletIdOf(senderId), walletIdOf(receiver.getId()), amount, note);
    }

    @Transactional(readOnly = true)
    public Account getWallet(UUID userId) {
        return accounts.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> history(UUID userId, int page, int size) {
        UUID walletId = walletIdOf(userId);
        return entries.findByAccountIdOrderByCreatedAtDesc(walletId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    // ------------------------------------------------------------------------------------------

    private TransactionResult execute(UUID initiatorId, String idempotencyKey, TransactionType type,
            UUID debitAccountId, UUID creditAccountId,
            long amount, String note) {
        if (amount <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw ApiException.badRequest("Idempotency-Key must be 1-100 characters");
        }

        // 1. Lock both accounts, lowest id first. Nothing that touches Account has been
        // loaded
        // before this point, so the locked rows are guaranteed fresh.
        Map<UUID, Account> locked = lockInOrder(debitAccountId, creditAccountId);

        // 2. Idempotency check AFTER the locks. If a concurrent request with the same
        // key was
        // holding the locks, it has committed by now and its record is visible here.
        String hash = fingerprint(type, debitAccountId, creditAccountId, amount);
        Optional<WalletTransaction> existing = transactions.findByInitiatorUserIdAndIdempotencyKey(initiatorId,
                idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw ApiException.idempotencyMismatch();
            }
            return new TransactionResult(existing.get(), true);
        }

        // 3. Apply. debit() throws INSUFFICIENT_FUNDS, which rolls the whole
        // transaction back.
        Account debitAccount = locked.get(debitAccountId);
        Account creditAccount = locked.get(creditAccountId);
        debitAccount.debit(amount);
        creditAccount.credit(amount);

        // 4. Record the transaction and its two ledger entries (insert order matters
        // for FKs).
        WalletTransaction txn = transactions.save(new WalletTransaction(
                initiatorId, idempotencyKey, hash, type,
                debitAccountId, creditAccountId, amount, note));
        entries.save(new LedgerEntry(txn.getId(), debitAccountId, EntryDirection.DEBIT,
                amount, debitAccount.getBalance()));
        entries.save(new LedgerEntry(txn.getId(), creditAccountId, EntryDirection.CREDIT,
                amount, creditAccount.getBalance()));

        // 5. Write outbox event IN THE SAME TRANSACTION so the event is never lost.
        outboxEvents.save(new OutboxEvent(txn.getId(), "wallet.transaction.created",
                buildEventPayload(txn)));

        return new TransactionResult(txn, false);
    }

    /**
     * Consistent global lock order (ascending UUID) is what prevents A->B / B->A
     * deadlocks.
     */
    private Map<UUID, Account> lockInOrder(UUID first, UUID second) {
        List<UUID> ordered = first.compareTo(second) <= 0 ? List.of(first, second) : List.of(second, first);
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : ordered) {
            locked.put(id, accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("Account not found")));
        }
        return locked;
    }

    private UUID walletIdOf(UUID userId) {
        return accounts.findIdByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
    }

    /** Minimal JSON payload for the outbox event (no extra Jackson dependency). */
    private static String buildEventPayload(WalletTransaction txn) {
        return "{" +
                "\"transactionId\":\"" + txn.getId() + "\"," +
                "\"type\":\"" + txn.getType() + "\"," +
                "\"sourceAccountId\":\"" + txn.getSourceAccountId() + "\"," +
                "\"destinationAccountId\":\"" + txn.getDestinationAccountId() + "\"," +
                "\"amount\":" + txn.getAmount() + "," +
                "\"createdAt\":\"" + txn.getCreatedAt() + "\"" +
                "}";
    }

    /** Detects an idempotency key being reused for a *different* request. */
    private static String fingerprint(TransactionType type, UUID debit, UUID credit, long amount) {
        String raw = type + "|" + debit + "|" + credit + "|" + amount;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
