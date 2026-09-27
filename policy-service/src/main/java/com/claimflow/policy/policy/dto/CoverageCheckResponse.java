package com.claimflow.policy.policy.dto;

import com.claimflow.policy.policy.CoverageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Answer to "does policy X cover loss type Y on date D?".
 * "Not covered" is a valid answer (200), not an error: the caller (Validation Service) decides what to do.
 */
public record CoverageCheckResponse(
        UUID policyId,
        String policyNumber,
        CoverageType coverageType,
        LocalDate incidentDate,
        boolean covered,
        Reason reason,
        BigDecimal limitAmount,
        BigDecimal deductible) {

    public enum Reason {
        COVERED,
        POLICY_CANCELLED,
        OUTSIDE_POLICY_PERIOD,
        COVERAGE_NOT_ON_POLICY
    }
}
