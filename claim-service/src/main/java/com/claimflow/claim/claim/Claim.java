package com.claimflow.claim.claim;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ConflictException;
import com.claimflow.common.money.Money;
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
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Claim aggregate. Status changes go only through the methods below, which consult the
 * {@link ClaimStatus} transition table; there is deliberately no setStatus().
 * Each operation returns a {@link StatusChange} that the service records in claim_history.
 */
@Entity
@Table(name = "claims")
public class Claim {

    private static final Set<ClaimStatus> ASSIGNABLE = EnumSet.of(ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW);

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String claimNumber;

    // Another service's aggregate: referenced by ID only, never by a JPA relationship or FK.
    @Column(nullable = false, updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LossType lossType;

    @Column(nullable = false, updatable = false)
    private LocalDate incidentDate;

    @Column(nullable = false, updatable = false)
    private Instant reportedAt;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal claimedAmount;

    @Column(precision = 15, scale = 2)
    private BigDecimal approvedAmount;

    // Confirmed by validation; carried to Payment in ClaimApproved.
    @Column(precision = 15, scale = 2)
    private BigDecimal coverageLimit;

    @Column(precision = 15, scale = 2)
    private BigDecimal deductible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    // Same service, but a separate aggregate: also referenced by ID.
    private UUID adjusterId;

    private String rejectionReason;

    @Column(updatable = false)
    private String idempotencyKey;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Claim() {
        // for JPA
    }

    /** First Notice of Loss: a new claim always starts in SUBMITTED. */
    public Claim(String claimNumber, UUID policyId, LossType lossType, LocalDate incidentDate, Instant reportedAt,
                 String description, BigDecimal claimedAmount, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.claimNumber = claimNumber;
        this.policyId = policyId;
        this.lossType = lossType;
        this.incidentDate = incidentDate;
        this.reportedAt = reportedAt;
        this.description = description;
        this.claimedAmount = Money.of(claimedAmount);   // scale 2 from the start (BUG-017)
        this.idempotencyKey = idempotencyKey;
        this.status = ClaimStatus.SUBMITTED;
    }

    public StatusChange creation() {
        return new StatusChange(null, ClaimStatus.SUBMITTED, HistoryEventType.CLAIM_CREATED,
                "FNOL: " + lossType + " on " + incidentDate + ", claimed " + claimedAmount);
    }

    // ---- lifecycle operations ----

    /** Generic transition, used for system-driven steps (validation result, payment progress). */
    public StatusChange transitionTo(ClaimStatus target, TransitionSource source, String details) {
        TransitionSource required = status.transitionSourceTo(target)
                .orElseThrow(() -> new InvalidStateTransitionException(claimNumber, status, target));
        if (required == TransitionSource.SYSTEM && source == TransitionSource.USER) {
            throw new BusinessRuleException("Status " + target + " is set automatically by the system; "
                    + "it cannot be requested through the API");
        }
        ClaimStatus old = status;
        status = target;
        return new StatusChange(old, target, HistoryEventType.forTransitionTo(target), details);
    }

    public StatusChange approve(BigDecimal amount) {
        // Check the transition first, so approving a CLOSED claim reports "invalid transition" (409),
        // not a less relevant rule like "no adjuster".
        requireTransition(ClaimStatus.APPROVED);
        if (adjusterId == null) {
            throw new BusinessRuleException("Claim " + claimNumber + " must have an adjuster before approval");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Approved amount must be greater than zero");
        }
        if (amount.compareTo(claimedAmount) > 0) {
            throw new BusinessRuleException("Approved amount " + amount + " exceeds claimed amount " + claimedAmount);
        }
        if (deductible != null && amount.compareTo(deductible) <= 0) {
            throw new BusinessRuleException("Approved amount " + amount + " does not exceed the deductible "
                    + deductible + ": nothing would be payable; reject the claim instead");
        }
        approvedAmount = Money.of(amount);
        return transitionTo(ClaimStatus.APPROVED, TransitionSource.USER, "Approved amount " + amount);
    }

    public StatusChange reject(String reason, TransitionSource source) {
        requireTransition(ClaimStatus.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("A rejection reason is required");
        }
        StatusChange change = transitionTo(ClaimStatus.REJECTED, source, reason);
        rejectionReason = reason;
        return change;
    }

    public StatusChange close() {
        return transitionTo(ClaimStatus.CLOSED, TransitionSource.USER, null);
    }

    /** Validation passed: remember the coverage terms it confirmed, then move to UNDER_REVIEW. */
    public StatusChange markValidated(BigDecimal coverageLimit, BigDecimal deductible, String details) {
        StatusChange change = transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.SYSTEM, details);
        this.coverageLimit = Money.of(coverageLimit);
        this.deductible = Money.of(deductible);
        return change;
    }

    public StatusChange assignAdjuster(UUID newAdjusterId) {
        if (!ASSIGNABLE.contains(status)) {
            throw new ConflictException("An adjuster can only be assigned while the claim is " + ASSIGNABLE
                    + "; claim " + claimNumber + " is " + status);
        }
        UUID previous = adjusterId;
        adjusterId = newAdjusterId;
        String details = previous == null ? "Assigned adjuster " + newAdjusterId
                : "Reassigned adjuster " + previous + " -> " + newAdjusterId;
        return new StatusChange(status, status, HistoryEventType.ADJUSTER_ASSIGNED, details);
    }

    private void requireTransition(ClaimStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(claimNumber, status, target);
        }
    }

    // ---- getters ----

    public UUID getId() {
        return id;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public LossType getLossType() {
        return lossType;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public BigDecimal getCoverageLimit() {
        return coverageLimit;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public UUID getAdjusterId() {
        return adjusterId;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
