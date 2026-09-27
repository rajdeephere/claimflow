package com.claimflow.payment.payment;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.PaymentCompleted;
import com.claimflow.common.events.payload.ClaimEvents.PaymentFailed;
import com.claimflow.payment.gateway.GatewayUnavailableException;
import com.claimflow.payment.gateway.PaymentGateway;
import com.claimflow.payment.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Moves the money for INITIATED payments.
 *
 * The bank call happens OUTSIDE any DB transaction (no DB connection or row lock held during a slow
 * external call). Safety comes from the idempotency key instead: the payment ID. If we crash after the
 * bank moved money but before we recorded it, the next run calls the bank again with the same key and
 * gets the original result back; the claim is never paid twice.
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public PaymentProcessor(PaymentRepository payments, PaymentGateway gateway, OutboxWriter outbox,
                            PlatformTransactionManager txManager, Clock clock,
                            @Value("${claimflow.payment.processor.batch-size:50}") int batchSize,
                            @Value("${claimflow.payment.processor.max-attempts:5}") int maxAttempts) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${claimflow.payment.processor.poll-interval-ms:1000}")
    public void processPending() {
        payments.findInitiatedIds(Limit.of(batchSize)).forEach(this::process);
    }

    public void process(UUID paymentId) {
        Payment snapshot = tx.execute(s -> payments.findById(paymentId).orElse(null));
        if (snapshot == null || snapshot.getStatus() != PaymentStatus.INITIATED) {
            return;
        }
        if (snapshot.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, snapshot.getCorrelationId());   // log lines + outgoing events
        }
        try {
            PaymentGateway.Result result;
            try {
                result = gateway.transfer(snapshot.getId(), snapshot.getClaimNumber(), snapshot.getAmount());
            } catch (GatewayUnavailableException e) {
                tx.executeWithoutResult(s -> recordUnavailable(paymentId, e.getMessage()));
                return;
            }
            tx.executeWithoutResult(s -> recordOutcome(paymentId, result));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another instance finished this payment first; the gateway's idempotency made our call harmless.
            log.info("Payment {} was completed concurrently by another instance", paymentId);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void recordOutcome(UUID paymentId, PaymentGateway.Result result) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.INITIATED) {
            return;
        }
        // instanceof patterns, not a pattern switch: the project targets Java 17 (switch patterns are Java 21)
        if (result instanceof PaymentGateway.Success success) {
            p.complete(success.reference(), Instant.now(clock));
            outbox.append(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_COMPLETED, p.getClaimId(),
                    new PaymentCompleted(p.getClaimId(), p.getId(), p.getAmount()));
            log.info("Payment {} for {} COMPLETED: {} (ref {})", p.getId(), p.getClaimNumber(), p.getAmount(),
                    success.reference());
        } else if (result instanceof PaymentGateway.Declined declined) {
            failAndAnnounce(p, "Declined by bank: " + declined.reason());
        }
    }

    private void recordUnavailable(UUID paymentId, String reason) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.INITIATED) {
            return;
        }
        p.recordUnavailable(reason);
        if (p.getAttempts() >= maxAttempts) {
            failAndAnnounce(p, "Payment gateway unavailable after " + p.getAttempts() + " attempts: " + reason);
        } else {
            log.warn("Payment {} for {}: gateway unavailable (attempt {}/{}), will retry: {}", p.getId(),
                    p.getClaimNumber(), p.getAttempts(), maxAttempts, reason);
        }
    }

    private void failAndAnnounce(Payment p, String reason) {
        p.fail(reason);
        outbox.append(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_FAILED, p.getClaimId(),
                new PaymentFailed(p.getClaimId(), p.getId(), reason));
        log.warn("Payment {} for {} FAILED: {}", p.getId(), p.getClaimNumber(), reason);
    }
}
