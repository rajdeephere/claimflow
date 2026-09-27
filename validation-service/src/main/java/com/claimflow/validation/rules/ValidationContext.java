package com.claimflow.validation.rules;

import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.validation.policy.CoverageCheck;

import java.time.Instant;

/**
 * Everything a rule may look at. Rules are pure functions of this context: no I/O, so each one
 * is unit-testable in isolation.
 *
 * @param reportedAt when the FNOL happened (the ClaimSubmitted event's occurredAt)
 */
public record ValidationContext(ClaimSubmitted claim, CoverageCheck coverage, Instant reportedAt) {

    public boolean hasCoverageTerms() {
        return coverage.limitAmount() != null && coverage.deductible() != null;
    }
}
