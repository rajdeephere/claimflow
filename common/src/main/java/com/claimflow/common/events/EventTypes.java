package com.claimflow.common.events;

/** Names on the wire. Past tense: an event is a fact that already happened. */
public final class EventTypes {

    public static final String CLAIM_SUBMITTED = "ClaimSubmitted";
    public static final String CLAIM_APPROVED = "ClaimApproved";
    public static final String CLAIM_REJECTED = "ClaimRejected";
    public static final String CLAIM_CLOSED = "ClaimClosed";

    public static final String CLAIM_VALIDATED = "ClaimValidated";
    public static final String CLAIM_VALIDATION_FAILED = "ClaimValidationFailed";

    public static final String PAYMENT_INITIATED = "PaymentInitiated";
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    private EventTypes() {
    }
}
