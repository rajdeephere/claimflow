package com.claimflow.validation.messaging;

import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.validation.ClaimValidator;
import com.claimflow.validation.ValidationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ClaimSubmittedListener {

    static final String GROUP = "validation-service";

    private static final Logger log = LoggerFactory.getLogger(ClaimSubmittedListener.class);

    private final ClaimValidator validator;
    private final ValidationResultPublisher publisher;
    private final ObjectMapper mapper;

    public ClaimSubmittedListener(ClaimValidator validator, ValidationResultPublisher publisher, ObjectMapper mapper) {
        this.validator = validator;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = Topics.CLAIM_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
        if (!EventTypes.CLAIM_SUBMITTED.equals(event.eventType())) {
            return;   // claim.events also carries ClaimApproved etc.; not our concern
        }
        ClaimSubmitted claim = mapper.treeToValue(event.payload(), ClaimSubmitted.class);

        ValidationOutcome outcome = validator.validate(claim, event.occurredAt());
        UUID resultId = publisher.publish(event, claim, outcome);

        if (outcome.valid()) {
            log.info("Claim {} VALID{} -> {}", claim.claimNumber(),
                    outcome.warnings().isEmpty() ? "" : " with warnings " + outcome.warnings(), resultId);
        } else {
            log.info("Claim {} INVALID {} -> {}", claim.claimNumber(), outcome.reasons(), resultId);
        }
    }
}
