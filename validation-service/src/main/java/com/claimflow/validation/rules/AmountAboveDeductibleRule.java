package com.claimflow.validation.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** If the loss doesn't exceed the deductible, the insurer pays nothing: reject early. */
@Component
@Order(30)
public class AmountAboveDeductibleRule implements ValidationRule {

    @Override
    public RuleResult evaluate(ValidationContext ctx) {
        if (!ctx.hasCoverageTerms()) {
            return RuleResult.pass();   // no coverage terms: CoverageOnPolicyRule reports that
        }
        if (ctx.claim().claimedAmount().compareTo(ctx.coverage().deductible()) <= 0) {
            return RuleResult.fail("Claimed amount " + ctx.claim().claimedAmount()
                    + " does not exceed the deductible " + ctx.coverage().deductible() + "; nothing is payable");
        }
        return RuleResult.pass();
    }
}
