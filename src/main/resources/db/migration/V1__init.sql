-- Money is stored as BIGINT minor units (paise). Never floats, never DECIMAL math in Java.

CREATE TABLE users (
    id            UUID                     PRIMARY KEY,
    name          VARCHAR(100)             NOT NULL,
    email         VARCHAR(255)             NOT NULL UNIQUE,
    password_hash VARCHAR(100)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE accounts (
    id           UUID                     PRIMARY KEY,
    user_id      UUID                     REFERENCES users (id),
    account_type VARCHAR(20)              NOT NULL,
    currency     VARCHAR(3)               NOT NULL,
    balance      BIGINT                   NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Last line of defence: even a buggy service cannot overdraw a user wallet.
    CONSTRAINT chk_user_balance_non_negative CHECK (account_type = 'SYSTEM' OR balance >= 0)
);

-- One wallet per user (the SYSTEM account has NULL user_id; NULLs do not collide).
CREATE UNIQUE INDEX uq_accounts_user ON accounts (user_id);

CREATE TABLE wallet_transactions (
    id                     UUID                     PRIMARY KEY,
    initiator_user_id      UUID                     NOT NULL REFERENCES users (id),
    idempotency_key        VARCHAR(100)             NOT NULL,
    request_hash           VARCHAR(64)              NOT NULL,
    type                   VARCHAR(20)              NOT NULL,
    source_account_id      UUID                     NOT NULL REFERENCES accounts (id),
    destination_account_id UUID                     NOT NULL REFERENCES accounts (id),
    amount                 BIGINT                   NOT NULL CHECK (amount > 0),
    note                   VARCHAR(255),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Backstop for idempotency: the same key can never produce two transactions.
    CONSTRAINT uq_txn_idempotency UNIQUE (initiator_user_id, idempotency_key)
);

-- Append-only. Every transaction has exactly one DEBIT and one CREDIT of equal amount.
CREATE TABLE ledger_entries (
    id             UUID                     PRIMARY KEY,
    transaction_id UUID                     NOT NULL REFERENCES wallet_transactions (id),
    account_id     UUID                     NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)               NOT NULL,
    amount         BIGINT                   NOT NULL CHECK (amount > 0),
    balance_after  BIGINT                   NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_entries_account_created ON ledger_entries (account_id, created_at);
CREATE INDEX idx_entries_txn ON ledger_entries (transaction_id);

-- The platform's funding account: the counter-party of every top-up.
-- It is allowed to go negative; the sum of ALL balances is always 0.
INSERT INTO accounts (id, user_id, account_type, currency, balance, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'INR', 0, CURRENT_TIMESTAMP);
