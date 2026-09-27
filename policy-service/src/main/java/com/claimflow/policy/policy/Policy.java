package com.claimflow.policy.policy;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ConflictException;
import com.claimflow.common.money.Money;
import com.claimflow.policy.customer.Customer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root: coverages are only created and changed through the policy,
 * so the policy can enforce its invariants (allowed coverage types, no duplicates).
 */
@Entity
@Table(name = "policies")
public class Policy {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String policyNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Coverage> coverages = new ArrayList<>();

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Policy() {
        // for JPA
    }

    public Policy(String policyNumber, Customer customer, ProductType productType,
                  LocalDate startDate, LocalDate endDate, BigDecimal premium) {
        if (!endDate.isAfter(startDate)) {
            throw new BusinessRuleException("Policy end date must be after start date");
        }
        this.id = UUID.randomUUID();
        this.policyNumber = policyNumber;
        this.customer = customer;
        this.productType = productType;
        this.status = PolicyStatus.ACTIVE;
        this.startDate = startDate;
        this.endDate = endDate;
        this.premium = Money.of(premium);   // scale 2 from the start (BUG-017)
    }

    public void addCoverage(CoverageType type, BigDecimal limitAmount, BigDecimal deductible) {
        if (!productType.allows(type)) {
            throw new BusinessRuleException(
                    "Coverage " + type + " is not offered for " + productType + " policies; allowed: "
                            + productType.allowedCoverages());
        }
        if (findCoverage(type).isPresent()) {
            throw new BusinessRuleException("Duplicate coverage " + type + " on policy");
        }
        if (deductible.compareTo(limitAmount) >= 0) {
            throw new BusinessRuleException("Deductible for " + type + " must be less than its limit");
        }
        coverages.add(new Coverage(this, type, limitAmount, deductible));
    }

    public void cancel() {
        if (status == PolicyStatus.CANCELLED) {
            throw new ConflictException("Policy " + policyNumber + " is already cancelled");
        }
        status = PolicyStatus.CANCELLED;
    }

    /** In force = not cancelled and the date is within [startDate, endDate] (both inclusive). */
    public boolean isInForceOn(LocalDate date) {
        return status == PolicyStatus.ACTIVE && !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public Optional<Coverage> findCoverage(CoverageType type) {
        return coverages.stream().filter(c -> c.getCoverageType() == type).findFirst();
    }

    public UUID getId() {
        return id;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public ProductType getProductType() {
        return productType;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getPremium() {
        return premium;
    }

    public List<Coverage> getCoverages() {
        return Collections.unmodifiableList(coverages);
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
