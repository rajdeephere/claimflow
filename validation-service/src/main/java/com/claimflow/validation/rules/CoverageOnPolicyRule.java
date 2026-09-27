package com.claimflow.validation.rules;

import com.claimflow.validation.policy.CoverageCheck.Reason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The policy must include a coverage for the type of loss claimed. */
@Component
@Order(20)
public class CoverageOnPolicyRule implements ValidationRule {

    @Override
    public RuleResult evaluate(ValidationContext ctx) {
        if (ctx.coverage().reason() == Reason.COVERAGE_NOT_ON_POLICY) {
            return RuleResult.fail("Policy " + ctx.coverage().policyNumber() + " has no "
                    + ctx.claim().lossType() + " coverage");
        }
        return RuleResult.pass();
    }
}
