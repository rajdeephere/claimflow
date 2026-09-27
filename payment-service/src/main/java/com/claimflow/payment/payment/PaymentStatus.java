package com.claimflow.payment.payment;

/** INITIATED -> COMPLETED, or INITIATED -> FAILED (declined, or gateway unavailable too many times). */
public enum PaymentStatus {
    INITIATED,
    COMPLETED,
    FAILED
}
