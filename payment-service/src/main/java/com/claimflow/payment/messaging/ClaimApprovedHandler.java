package com.claimflow.payment.messaging;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.ClaimApproved;
import com.claimflow.common.events.payload.ClaimEvents.PaymentInitiated;
import com.claimflow.payment.outbox.OutboxWriter;
import com.claimflow.payment.payment.Payment;
import com.claimflow.payment.payment.PaymentRepository;
import com.claimflow.payment.settlement.Settlement;
import com.claimflow.payment.settlement.SettlementCalculator;
import com.claimflow.payment.settlement.SettlementRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * ClaimApproved -> settlement + INITIATED payment + PaymentInitiated (outbox), in ONE transaction.
 * The money itself is moved later by PaymentProcessor, outside any DB transaction.
 *
 * Three guards against paying a claim twice (ADR-0023):
 *   1. processed_events: the same event is handled once
 *   2. "does this claim already have a payment?": a different event for the same claim (replay, re-approval)
 *   3. UNIQUE (claim_id) on payments: the database's final word, even if 1 and 2 had a bug
 */
@Component
public class ClaimApprovedHandler {

    static final String CONSUMER = "payment-service";

    private static final Logger log = LoggerFactory.getLogger(ClaimApprovedHandler.class);

    private final ProcessedEventStore processed;
    private final PaymentRepository payments;
    private final SettlementRepository settlements;
    private final SettlementCalculator calculator;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ClaimApprovedHandler(ProcessedEventStore processed, PaymentRepository payments,
                                SettlementRepository settlements, SettlementCalculator calculator, OutboxWriter outbox,
                                ObjectMapper mapper, Clock clock) {
        this.processed = processed;
        this.payments = payments;
        this.settlements = settlements;
        this.calculator = calculator;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void handle(EventEnvelope event) throws JsonProcessingException {
        if (!processed.markProcessed(event.eventId(), CONSUMER, event.eventType(), Instant.now(clock))) {
            log.info("Duplicate {} {}; already processed, skipping", event.eventType(), event.eventId());
            return;
        }
        ClaimApproved approved = mapper.treeToValue(event.payload(), ClaimApproved.class);

        if (payments.existsByClaimId(approved.claimId())) {
            log.warn("Claim {} already has a payment; ignoring {} {}", approved.claimNumber(), event.eventType(),
                    event.eventId());
            return;
        }
        if (approved.coverageLimit() == null || approved.deductible() == null) {
            // Can't settle without terms. Non-retryable (IllegalArgumentException), so DLT for a human.
            throw new IllegalArgumentException("ClaimApproved for " + approved.claimNumber()
                    + " has no coverage terms (limit/deductible)");
        }

        SettlementCalculator.Result result =
                calculator.calculate(approved.approvedAmount(), approved.deductible(), approved.coverageLimit());
        Settlement settlement = settlements.save(new Settlement(approved.claimId(), approved.claimNumber(),
                approved.claimedAmount(), approved.approvedAmount(), approved.deductible(), approved.coverageLimit(),
                result, Instant.now(clock)));
        Payment payment = payments.save(new Payment(approved.claimId(), approved.claimNumber(), settlement.getId(),
                result.payable(), CorrelationId.current()));

        outbox.append(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_INITIATED, approved.claimId(),
                new PaymentInitiated(approved.claimId(), payment.getId(), payment.getAmount()));
        log.info("Settlement for {}: approved {} - deductible {} = {}{}; payment {} INITIATED",
                approved.claimNumber(), approved.approvedAmount(), approved.deductible(), result.payable(),
                result.cappedAtLimit() ? " (capped at limit " + approved.coverageLimit() + ")" : "", payment.getId());
    }
}
