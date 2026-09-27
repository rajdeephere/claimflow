package com.claimflow.claim.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Remembers which events a consumer has already processed (ADR-0009). */
@Component
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the event for this consumer. Returns false if it was already processed.
     *
     * ON CONFLICT DO NOTHING instead of catching a duplicate-key exception: a constraint violation
     * would mark the surrounding transaction rollback-only (see ADR-0017), whereas this simply
     * reports 0 rows inserted. Concurrent duplicates are still safe: the second insert waits on the
     * first transaction's row lock, then sees the conflict.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer, String eventType, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, consumer_name, event_type, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id, consumer_name) DO NOTHING""",
                eventId, consumer, eventType, Timestamp.from(now));
        return inserted == 1;
    }
}
