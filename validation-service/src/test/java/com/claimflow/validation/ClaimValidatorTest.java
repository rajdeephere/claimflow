package com.claimflow.validation;

import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.validation.policy.CoverageCheck;
import com.claimflow.validation.policy.CoverageCheck.Reason;
import com.claimflow.validation.policy.PolicyClient;
import com.claimflow.validation.rules.AmountAboveDeductibleRule;
import com.claimflow.validation.rules.CoverageLimitRule;
import com.claimflow.validation.rules.CoverageOnPolicyRule;
import com.claimflow.validation.rules.LateNotificationRule;
import com.claimflow.validation.rules.PolicyInForceRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimValidatorTest {

    private static final LocalDate INCIDENT = LocalDate.of(2026, 3, 10);

    @Mock
    PolicyClient policyClient;

    private ClaimValidator validator() {
        return new ClaimValidator(policyClient, List.of(new PolicyInForceRule(), new CoverageOnPolicyRule(),
                new AmountAboveDeductibleRule(), new CoverageLimitRule(), new LateNotificationRule(30)));
    }

    private static ClaimSubmitted claim(String amount) {
        return new ClaimSubmitted(UUID.randomUUID(), "CLM-2026-000001", UUID.randomUUID(), "COLLISION",
                INCIDENT, new BigDecimal(amount));
    }

    private void policyAnswers(ClaimSubmitted c, Reason reason, String limit, String deductible) {
        when(policyClient.checkCoverage(c.policyId(), "COLLISION", INCIDENT)).thenReturn(Optional.of(
                new CoverageCheck(c.policyId(), "POL-2026-000042", "COLLISION", INCIDENT, reason == Reason.COVERED,
                        reason, limit == null ? null : new BigDecimal(limit),
                        deductible == null ? null : new BigDecimal(deductible))));
    }

    @Test
    void validClaimPassesWithCoverageTerms() {
        ClaimSubmitted c = claim("200000.00");
        policyAnswers(c, Reason.COVERED, "500000.00", "20000.00");

        ValidationOutcome o = validator().validate(c, Instant.parse("2026-03-11T00:00:00Z"));

        assertThat(o.valid()).isTrue();
        assertThat(o.coverageLimit()).isEqualTo(new BigDecimal("500000.00"));
        assertThat(o.deductible()).isEqualTo(new BigDecimal("20000.00"));
        assertThat(o.warnings()).isEmpty();
    }

    @Test
    void unknownPolicyFails() {
        ClaimSubmitted c = claim("1000");
        when(policyClient.checkCoverage(c.policyId(), "COLLISION", INCIDENT)).thenReturn(Optional.empty());

        ValidationOutcome o = validator().validate(c, Instant.parse("2026-03-11T00:00:00Z"));

        assertThat(o.valid()).isFalse();
        assertThat(o.reasons()).singleElement().asString().contains("not found");
    }

    @Test
    void warningsDoNotFailTheClaimButAreAllReported() {
        ClaimSubmitted c = claim("900000.00");   // above limit + deductible
        policyAnswers(c, Reason.COVERED, "500000.00", "20000.00");

        ValidationOutcome o = validator().validate(c, Instant.parse("2026-05-01T00:00:00Z"));   // 52 days late

        assertThat(o.valid()).isTrue();
        assertThat(o.warnings()).hasSize(2)
                .anyMatch(w -> w.contains("capped"))
                .anyMatch(w -> w.contains("days after the incident"));
    }

    @Test
    void cancelledPolicyFailsWithReason() {
        ClaimSubmitted c = claim("1000");
        policyAnswers(c, Reason.POLICY_CANCELLED, "500000", "20000");

        ValidationOutcome o = validator().validate(c, Instant.parse("2026-03-11T00:00:00Z"));

        assertThat(o.valid()).isFalse();
        // the claim is also below the deductible: ALL reasons are reported, not only the first
        assertThat(o.reasons()).hasSize(2)
                .anyMatch(r -> r.contains("cancelled"))
                .anyMatch(r -> r.contains("deductible"));
    }
}
