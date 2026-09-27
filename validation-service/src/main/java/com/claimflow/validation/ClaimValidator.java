package com.claimflow.validation;

import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.validation.policy.CoverageCheck;
import com.claimflow.validation.policy.PolicyClient;
import com.claimflow.validation.rules.RuleResult;
import com.claimflow.validation.rules.ValidationContext;
import com.claimflow.validation.rules.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the policy's coverage once, then runs EVERY rule, so a rejected claim lists all its
 * problems, not just the first one found.
 */
@Component
public class ClaimValidator {

    private final PolicyClient policyClient;
    private final List<ValidationRule> rules;   // all ValidationRule beans, in @Order order

    public ClaimValidator(PolicyClient policyClient, List<ValidationRule> rules) {
        this.policyClient = policyClient;
        this.rules = rules;
    }

    public ValidationOutcome validate(ClaimSubmitted claim, Instant reportedAt) {
        Optional<CoverageCheck> coverage =
                policyClient.checkCoverage(claim.policyId(), claim.lossType(), claim.incidentDate());
        if (coverage.isEmpty()) {
            return ValidationOutcome.failed(List.of("Policy " + claim.policyId() + " not found"));
        }

        ValidationContext ctx = new ValidationContext(claim, coverage.get(), reportedAt);
        List<RuleResult> results = rules.stream().map(rule -> rule.evaluate(ctx)).toList();
        List<String> failures = messages(results, RuleResult.Outcome.FAIL);
        List<String> warnings = messages(results, RuleResult.Outcome.WARN);

        return failures.isEmpty()
                ? ValidationOutcome.passed(coverage.get().limitAmount(), coverage.get().deductible(), warnings)
                : ValidationOutcome.failed(failures);
    }

    public List<ValidationRule> rules() {
        return rules;
    }

    private static List<String> messages(List<RuleResult> results, RuleResult.Outcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).map(RuleResult::message).toList();
    }
}
