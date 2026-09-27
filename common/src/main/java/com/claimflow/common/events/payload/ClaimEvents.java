package com.claimflow.common.events.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload contracts for every event type. Grouped in one file so the whole event catalogue is
 * visible at a glance (see docs/kafka-events.md). Amounts are BigDecimal, serialised as JSON numbers.
 */
public final class ClaimEvents {

    private ClaimEvents() {
    }

    // ---- claim.events (producer: claim-service) ----

    public record ClaimSubmitted(UUID claimId, String claimNumber, UUID policyId, String lossType,
                                 LocalDate incidentDate, BigDecimal claimedAmount) {
    }

    public record ClaimApproved(UUID claimId, String claimNumber, UUID policyId, String lossType,
                                LocalDate incidentDate, BigDecimal claimedAmount, BigDecimal approvedAmount) {
    }

    public record ClaimRejected(UUID claimId, String claimNumber, String reason) {
    }

    public record ClaimClosed(UUID claimId, String claimNumber) {
    }

    // ---- validation.events (producer: validation-service) ----

    public record ClaimValidated(UUID claimId, BigDecimal coverageLimit, BigDecimal deductible) {
    }

    public record ClaimValidationFailed(UUID claimId, List<String> reasons) {
    }

    // ---- payment.events (producer: payment-service) ----

    public record PaymentInitiated(UUID claimId, UUID paymentId, BigDecimal amount) {
    }

    public record PaymentCompleted(UUID claimId, UUID paymentId, BigDecimal amount) {
    }

    public record PaymentFailed(UUID claimId, UUID paymentId, String reason) {
    }
}
