package com.claimflow.payment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    private static final int MAX_REASON = 500;

    /** Also the idempotency key for the gateway: retrying a transfer with it can never pay twice. */
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID claimId;

    @Column(nullable = false, updatable = false)
    private String claimNumber;

    @Column(nullable = false, updatable = false)
    private UUID settlementId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String gatewayReference;

    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    // The business transaction that approved the claim; the processor restores it for its log lines and events.
    @Column(updatable = false)
    private String correlationId;

    // Two processor instances finishing the same payment: the second commit fails instead of overwriting.
    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(UUID claimId, String claimNumber, UUID settlementId, BigDecimal amount, String correlationId) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.claimNumber = claimNumber;
        this.settlementId = settlementId;
        this.amount = amount;
        this.correlationId = correlationId;
        this.status = PaymentStatus.INITIATED;
    }

    public void complete(String reference, Instant when) {
        requireInitiated();
        attempts++;
        status = PaymentStatus.COMPLETED;
        gatewayReference = reference;
        completedAt = when;
        failureReason = null;
    }

    public void fail(String reason) {
        requireInitiated();
        attempts++;
        status = PaymentStatus.FAILED;
        failureReason = truncate(reason);
    }

    /** The gateway couldn't be reached: stay INITIATED and try again later. */
    public void recordUnavailable(String reason) {
        requireInitiated();
        attempts++;
        failureReason = truncate(reason);
    }

    private void requireInitiated() {
        if (status != PaymentStatus.INITIATED) {
            throw new IllegalStateException("Payment " + id + " is already " + status);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), MAX_REASON));
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
