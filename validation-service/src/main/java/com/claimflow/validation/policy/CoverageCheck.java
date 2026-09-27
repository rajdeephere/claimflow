package com.claimflow.validation.policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Policy Service's coverage-check answer. Our own copy of the JSON contract: services share the
 * wire format, not Java classes, so Policy Service can change internally without breaking us.
 */
public record CoverageCheck(
        UUID policyId,
        String policyNumber,
        String coverageType,
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
