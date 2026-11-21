package com.example.wallet.repo;

import com.example.wallet.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns the oldest unprocessed events (up to {@code limit}) ordered by
     * insertion time so events are relayed in causal order.
     */
    @Query(value = "SELECT * FROM outbox_events ORDER BY created_at ASC LIMIT :limit",
           nativeQuery = true)
    List<OutboxEvent> findOldestEvents(int limit);
}
