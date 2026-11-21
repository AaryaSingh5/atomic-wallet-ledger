-- Transactional outbox table.
-- Events are written in the SAME database transaction as the business data,
-- then picked up by the OutboxRelayService and published to Kafka.
-- Once published the row is deleted; the table is intentionally tiny.

CREATE TABLE outbox_events (
    id           UUID                     PRIMARY KEY,
    aggregate_id UUID                     NOT NULL,       -- wallet_transactions.id
    event_type   VARCHAR(80)              NOT NULL,       -- e.g. "wallet.transaction.created"
    payload      TEXT                     NOT NULL,       -- JSON
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_outbox_created ON outbox_events (created_at);
