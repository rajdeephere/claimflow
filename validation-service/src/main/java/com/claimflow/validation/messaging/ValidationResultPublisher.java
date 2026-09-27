package com.claimflow.validation.messaging;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.common.events.payload.ClaimEvents.ClaimValidated;
import com.claimflow.common.events.payload.ClaimEvents.ClaimValidationFailed;
import com.claimflow.common.kafka.KafkaHeaderNames;
import com.claimflow.validation.ValidationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the validation result directly to Kafka. There is no outbox: this service has no
 * database, so there is no dual write. If the send fails, the exception makes Kafka redeliver the
 * ClaimSubmitted (offset not committed) and we simply validate and publish again.
 *
 * Stateless idempotency (ADR-0021): the output eventId is DERIVED from the input eventId, so
 * reprocessing the same ClaimSubmitted yields an event with the same ID, and claim-service's
 * processed_events drops the duplicate.
 */
@Component
public class ValidationResultPublisher {

    static final String SOURCE = "validation-service";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long sendTimeoutMs;

    public ValidationResultPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, Clock clock,
                                     @Value("${claimflow.kafka.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** Deterministic: same input event, same output event ID. */
    static UUID resultEventId(UUID claimSubmittedEventId) {
        return UUID.nameUUIDFromBytes((SOURCE + ":" + claimSubmittedEventId).getBytes(StandardCharsets.UTF_8));
    }

    public UUID publish(EventEnvelope source, ClaimSubmitted claim, ValidationOutcome outcome) throws Exception {
        UUID eventId = resultEventId(source.eventId());
        String type = outcome.valid() ? EventTypes.CLAIM_VALIDATED : EventTypes.CLAIM_VALIDATION_FAILED;
        Object payload = outcome.valid()
                ? new ClaimValidated(claim.claimId(), outcome.coverageLimit(), outcome.deductible(), outcome.warnings())
                : new ClaimValidationFailed(claim.claimId(), outcome.reasons());
        String correlationId = CorrelationId.current();

        EventEnvelope envelope = new EventEnvelope(eventId, type, 1, Instant.now(clock), SOURCE, claim.claimId(),
                correlationId, mapper.valueToTree(payload));
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.VALIDATION_EVENTS,
                claim.claimId().toString(), mapper.writeValueAsString(envelope));
        if (correlationId != null) {
            record.headers().add(KafkaHeaderNames.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add(KafkaHeaderNames.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaderNames.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));

        kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);   // fail the listener if not acked
        return eventId;
    }
}
