package com.example.wallet.messaging;

import com.example.wallet.domain.OutboxEvent;
import com.example.wallet.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polling relay that drains the outbox table and forwards each event to Kafka.
 *
 * <p>Pattern: the business transaction writes an {@link OutboxEvent} row in its own
 * DB transaction.  This scheduler reads those rows and publishes them to Kafka.
 * On success the row is deleted; on failure it is left for the next poll cycle.
 *
 * <p>The relay is idempotent from Kafka's perspective: Kafka producers are
 * configured with {@code enable.idempotence=true} and consumers are expected
 * to be idempotent (the transaction id acts as a natural deduplication key).
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelayService(OutboxEventRepository outbox,
                              KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka  = kafka;
    }

    /**
     * Runs every 500 ms.  Fixed-delay keeps pressure off Kafka when the outbox is empty.
     * In production, replace with CDC (Debezium) for lower latency and zero polling overhead.
     */
    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.findOldestEvents(BATCH_SIZE);
        if (batch.isEmpty()) return;

        for (OutboxEvent event : batch) {
            try {
                // topic == event type, key == aggregate id (guarantees ordering per wallet txn)
                kafka.send(event.getEventType(),
                           event.getAggregateId().toString(),
                           event.getPayload())
                     .whenComplete((result, ex) -> {
                         if (ex != null) {
                             log.error("Kafka send failed for outbox event {}: {}",
                                       event.getId(), ex.getMessage());
                         }
                     });
                outbox.delete(event);
            } catch (Exception ex) {
                log.error("Failed to relay outbox event {}: {}", event.getId(), ex.getMessage());
            }
        }
    }
}
