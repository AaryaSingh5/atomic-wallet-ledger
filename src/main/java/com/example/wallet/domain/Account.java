package com.example.wallet.domain;

import com.example.wallet.api.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet. {@code balance} is a cached running total (in paise) that is always updated in the
 * same DB transaction as the ledger entries that justify it. The ledger is the source of truth;
 * the reconciliation job proves the two agree.
 */
@Entity
@Table(name = "accounts")
public class Account extends BaseEntity {

    /** Fixed id of the platform funding account seeded by the Flyway migration. */
    public static final UUID SYSTEM_FUNDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    private Account(UUID userId, AccountType accountType) {
        super(UUID.randomUUID());
        this.userId = userId;
        this.accountType = accountType;
        this.currency = "INR";
        this.balance = 0L;
        this.createdAt = Instant.now();
    }

    public static Account forUser(UUID userId) {
        return new Account(userId, AccountType.USER);
    }

    public void debit(long amount) {
        if (accountType == AccountType.USER && balance < amount) {
            throw ApiException.insufficientFunds();
        }
        balance = Math.subtractExact(balance, amount);
    }

    public void credit(long amount) {
        balance = Math.addExact(balance, amount);
    }

    public UUID getUserId() {
        return userId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
