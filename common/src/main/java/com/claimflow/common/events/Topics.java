package com.claimflow.common.events;

/**
 * One topic per producing service, keyed by claimId so all events of a claim stay in order
 * within one partition.
 */
public final class Topics {

    /** Produced by claim-service: ClaimSubmitted, ClaimApproved, ClaimRejected, ClaimClosed. */
    public static final String CLAIM_EVENTS = "claim.events";
    /** Produced by validation-service: ClaimValidated, ClaimValidationFailed. */
    public static final String VALIDATION_EVENTS = "validation.events";
    /** Produced by payment-service: PaymentInitiated, PaymentCompleted, PaymentFailed. */
    public static final String PAYMENT_EVENTS = "payment.events";

    /** Suffix Spring Kafka's DeadLetterPublishingRecoverer appends by default. */
    public static final String DLT_SUFFIX = ".DLT";

    public static final int PARTITIONS = 3;

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
