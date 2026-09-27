package com.claimflow.claim.messaging;

import com.claimflow.claim.claim.ClaimService;
import com.claimflow.claim.claim.ClaimStatus;
import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.payload.ClaimEvents.ClaimValidated;
import com.claimflow.common.events.payload.ClaimEvents.ClaimValidationFailed;
import com.claimflow.common.events.payload.ClaimEvents.PaymentCompleted;
import com.claimflow.common.events.payload.ClaimEvents.PaymentFailed;
import com.claimflow.common.events.payload.ClaimEvents.PaymentInitiated;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Applies validation and payment outcomes to claims.
 *
 * One transaction covers: the processed_events row, the claim transition, its history row, and any
 * event it emits (via the outbox). So consume-and-produce is atomic without Kafka transactions:
 * either all of it commits, or none of it does and the record is redelivered.
 */
@Component
public class InboundEventHandler {

    static final String CONSUMER = "claim-service";

    private static final Logger log = LoggerFactory.getLogger(InboundEventHandler.class);

    private final ProcessedEventStore processed;
    private final ClaimService claims;
    private final ObjectMapper mapper;
    private final Clock clock;

    public InboundEventHandler(ProcessedEventStore processed, ClaimService claims, ObjectMapper mapper, Clock clock) {
        this.processed = processed;
        this.claims = claims;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void handle(EventEnvelope event) throws JsonProcessingException {
        if (!processed.markProcessed(event.eventId(), CONSUMER, event.eventType(), Instant.now(clock))) {
            log.info("Duplicate {} {} for claim {}; already processed, skipping", event.eventType(),
                    event.eventId(), event.aggregateId());
            return;
        }

        switch (event.eventType()) {
            case EventTypes.CLAIM_VALIDATED -> {
                ClaimValidated p = mapper.treeToValue(event.payload(), ClaimValidated.class);
                claims.applySystemTransition(p.claimId(), ClaimStatus.UNDER_REVIEW,
                        "Validated: coverage limit " + p.coverageLimit() + ", deductible " + p.deductible());
            }
            case EventTypes.CLAIM_VALIDATION_FAILED -> {
                ClaimValidationFailed p = mapper.treeToValue(event.payload(), ClaimValidationFailed.class);
                claims.applySystemTransition(p.claimId(), ClaimStatus.REJECTED,
                        "Validation failed: " + String.join("; ", p.reasons()));
            }
            case EventTypes.PAYMENT_INITIATED -> {
                PaymentInitiated p = mapper.treeToValue(event.payload(), PaymentInitiated.class);
                claims.applySystemTransition(p.claimId(), ClaimStatus.PAYMENT_INITIATED,
                        "Payment " + p.paymentId() + " initiated for " + p.amount());
            }
            case EventTypes.PAYMENT_COMPLETED -> {
                PaymentCompleted p = mapper.treeToValue(event.payload(), PaymentCompleted.class);
                claims.applySystemTransition(p.claimId(), ClaimStatus.SETTLED,
                        "Payment " + p.paymentId() + " completed: " + p.amount());
            }
            case EventTypes.PAYMENT_FAILED -> {
                PaymentFailed p = mapper.treeToValue(event.payload(), PaymentFailed.class);
                claims.recordPaymentFailure(p.claimId(), "Payment " + p.paymentId() + " failed: " + p.reason());
            }
            // Tolerant reader: a producer may add new event types before we understand them.
            default -> log.info("Ignoring event type {} ({})", event.eventType(), event.eventId());
        }
    }
}
