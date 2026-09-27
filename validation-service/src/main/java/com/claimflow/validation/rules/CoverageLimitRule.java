package com.claimflow.validation.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A claim above the coverage limit is still valid: the payout is capped at the limit (Payment
 * Service does the settlement maths). The adjuster should know, so it's a warning, not a failure.
 */
@Component
@Order(40)
public class CoverageLimitRule implements ValidationRule {

    @Override
    public RuleResult evaluate(ValidationContext ctx) {
        if (!ctx.hasCoverageTerms()) {
            return RuleResult.pass();
        }
        BigDecimal payableBeforeCap = ctx.claim().claimedAmount().subtract(ctx.coverage().deductible());
        if (payableBeforeCap.compareTo(ctx.coverage().limitAmount()) > 0) {
            return RuleResult.warn("Claimed amount minus deductible (" + payableBeforeCap
                    + ") exceeds the coverage limit " + ctx.coverage().limitAmount() + "; payout will be capped");
        }
        return RuleResult.pass();
    }
}
