package com.claimflow.claim.outbox;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.events.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Appends an event to the outbox as part of the caller's transaction. It never talks to Kafka.
 * The correlation ID is captured NOW (from the MDC of the HTTP request or the Kafka record being
 * processed), because the relay publishes later on a scheduler thread that has no MDC.
 */
@Component
public class OutboxWriter {

    static final String SOURCE = "claim-service";
    static final int PAYLOAD_VERSION = 1;

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper, Clock clock) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    // MANDATORY: writing an outbox row outside a business transaction would defeat its purpose.
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String eventType, UUID aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String correlationId = CorrelationId.current();
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, PAYLOAD_VERSION, now, SOURCE, aggregateId,
                correlationId, mapper.valueToTree(payload));
        try {
            outbox.save(new OutboxEvent(eventId, topic, aggregateId.toString(), eventType,
                    mapper.writeValueAsString(envelope), correlationId, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + eventType, e);
        }
        return eventId;
    }
}
