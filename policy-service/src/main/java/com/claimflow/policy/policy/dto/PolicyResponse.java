package com.claimflow.policy.policy.dto;

import com.claimflow.policy.policy.CoverageType;
import com.claimflow.policy.policy.Policy;
import com.claimflow.policy.policy.PolicyStatus;
import com.claimflow.policy.policy.ProductType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyResponse(
        UUID id,
        String policyNumber,
        UUID customerId,
        ProductType productType,
        PolicyStatus status,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal premium,
        List<CoverageResponse> coverages,
        Long version,
        Instant createdAt) {

    public record CoverageResponse(CoverageType coverageType, BigDecimal limitAmount, BigDecimal deductible) {
    }

    public static PolicyResponse from(Policy p) {
        List<CoverageResponse> coverages = p.getCoverages().stream()
                .map(c -> new CoverageResponse(c.getCoverageType(), c.getLimitAmount(), c.getDeductible()))
                .toList();
        return new PolicyResponse(p.getId(), p.getPolicyNumber(), p.getCustomer().getId(), p.getProductType(),
                p.getStatus(), p.getStartDate(), p.getEndDate(), p.getPremium(), coverages, p.getVersion(),
                p.getCreatedAt());
    }
}
