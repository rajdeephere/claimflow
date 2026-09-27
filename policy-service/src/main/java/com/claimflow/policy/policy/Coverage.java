package com.claimflow.policy.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "coverages")
public class Coverage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private Policy policy;

    @Enumerated(EnumType.STRING)   // store the name, never the ordinal: reordering the enum must not corrupt data
    @Column(nullable = false)
    private CoverageType coverageType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal limitAmount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal deductible;

    protected Coverage() {
        // for JPA
    }

    Coverage(Policy policy, CoverageType coverageType, BigDecimal limitAmount, BigDecimal deductible) {
        this.id = UUID.randomUUID();
        this.policy = policy;
        this.coverageType = coverageType;
        this.limitAmount = limitAmount;
        this.deductible = deductible;
    }

    public UUID getId() {
        return id;
    }

    public CoverageType getCoverageType() {
        return coverageType;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }
}
