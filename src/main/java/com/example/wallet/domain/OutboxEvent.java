package com.example.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox record.
 * Written in the same DB transaction as the business operation, then relayed
 * to Kafka by {@link com.example.wallet.messaging.OutboxRelayService}.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /** JSON-serialised event payload. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID aggregateId, String eventType, String payload) {
        super(UUID.randomUUID());
        this.aggregateId = aggregateId;
        this.eventType   = eventType;
        this.payload     = payload;
        this.createdAt   = Instant.now();
    }

    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload()   { return payload; }
    public Instant getCreatedAt(){ return createdAt; }
}
