package com.claimflow.claim.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Next unpublished events in write order, row-locked for this transaction.
     * SKIP LOCKED lets several instances of claim-service run the relay at once:
     * each grabs a different batch instead of waiting on (or double-publishing) the same rows.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int limit);

    long countByPublishedAtIsNull();
}
