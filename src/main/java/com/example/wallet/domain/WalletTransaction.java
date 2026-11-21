package com.example.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One business operation (top-up or transfer). It owns exactly two ledger entries. */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction extends BaseEntity {

    @Column(name = "initiator_user_id", nullable = false)
    private UUID initiatorUserId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletTransaction() {
    }

    public WalletTransaction(UUID initiatorUserId, String idempotencyKey, String requestHash,
                             TransactionType type, UUID sourceAccountId, UUID destinationAccountId,
                             long amount, String note) {
        super(UUID.randomUUID());
        this.initiatorUserId = initiatorUserId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.type = type;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public UUID getInitiatorUserId() {
        return initiatorUserId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public TransactionType getType() {
        return type;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
