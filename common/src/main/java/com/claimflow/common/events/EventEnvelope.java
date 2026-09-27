package com.claimflow.common.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Every Kafka message in ClaimFlow is one of these, serialised as JSON (ADR-0019).
 *
 * @param eventId       unique per event; consumers use it for idempotency (processed_events)
 * @param eventType     e.g. "ClaimSubmitted"; consumers switch on it and ignore types they don't know
 * @param version       payload schema version, bumped on breaking changes
 * @param occurredAt    when the business fact happened (not when it was published)
 * @param source        producing service
 * @param aggregateId   the claim the event is about; also the Kafka message key (ordering per claim)
 * @param correlationId the business transaction it belongs to (also sent as a Kafka header)
 * @param payload       event-specific data; parsed by the consumer into its own type
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String source,
        UUID aggregateId,
        String correlationId,
        JsonNode payload) {
}
