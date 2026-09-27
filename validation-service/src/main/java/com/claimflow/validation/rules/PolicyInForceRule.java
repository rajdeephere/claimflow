package com.claimflow.validation.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The policy must be active and the incident date inside the policy period. */
@Component
@Order(10)
public class PolicyInForceRule implements ValidationRule {

    @Override
    public RuleResult evaluate(ValidationContext ctx) {
        return switch (ctx.coverage().reason()) {
            case POLICY_CANCELLED -> RuleResult.fail("Policy " + ctx.coverage().policyNumber() + " is cancelled");
            case OUTSIDE_POLICY_PERIOD -> RuleResult.fail("Incident date " + ctx.claim().incidentDate()
                    + " is outside the period of policy " + ctx.coverage().policyNumber());
            default -> RuleResult.pass();
        };
    }
}
