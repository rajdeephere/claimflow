package com.claimflow.claim.claim;

/** Audit event types recorded in claim_history. */
public enum HistoryEventType {
    CLAIM_CREATED,
    CLAIM_VALIDATED,
    CLAIM_REJECTED,
    ADJUSTER_ASSIGNED,
    CLAIM_APPROVED,
    SETTLEMENT_REQUESTED,
    PAYMENT_INITIATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    CLAIM_CLOSED;

    /** The audit event recorded when a claim enters {@code status}. */
    static HistoryEventType forTransitionTo(ClaimStatus status) {
        return switch (status) {
            case SUBMITTED -> CLAIM_CREATED;
            case UNDER_REVIEW -> CLAIM_VALIDATED;
            case APPROVED -> CLAIM_APPROVED;
            case REJECTED -> CLAIM_REJECTED;
            case SETTLEMENT_PENDING -> SETTLEMENT_REQUESTED;
            case PAYMENT_INITIATED -> PAYMENT_INITIATED;
            case SETTLED -> PAYMENT_COMPLETED;
            case CLOSED -> CLAIM_CLOSED;
        };
    }
}
