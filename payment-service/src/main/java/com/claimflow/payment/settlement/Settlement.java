package com.claimflow.payment.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The inputs and result of one settlement calculation. Written once, never changed. */
@Entity
@Immutable
@Table(name = "settlements")
public class Settlement {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID claimId;

    @Column(nullable = false, updatable = false)
    private String claimNumber;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal claimedAmount;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal approvedAmount;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal deductible;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal coverageLimit;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal payableAmount;

    @Column(nullable = false, updatable = false)
    private boolean cappedAtLimit;

    @Column(nullable = false, updatable = false)
    private Instant calculatedAt;

    protected Settlement() {
        // for JPA
    }

    public Settlement(UUID claimId, String claimNumber, BigDecimal claimedAmount, BigDecimal approvedAmount,
                      BigDecimal deductible, BigDecimal coverageLimit, SettlementCalculator.Result result,
                      Instant calculatedAt) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.claimNumber = claimNumber;
        this.claimedAmount = claimedAmount;
        this.approvedAmount = approvedAmount;
        this.deductible = deductible;
        this.coverageLimit = coverageLimit;
        this.payableAmount = result.payable();
        this.cappedAtLimit = result.cappedAtLimit();
        this.calculatedAt = calculatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }

    public BigDecimal getCoverageLimit() {
        return coverageLimit;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public boolean isCappedAtLimit() {
        return cappedAtLimit;
    }
}
