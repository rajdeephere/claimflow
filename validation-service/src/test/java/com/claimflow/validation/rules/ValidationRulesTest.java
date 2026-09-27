package com.claimflow.validation.rules;

import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.validation.policy.CoverageCheck;
import com.claimflow.validation.policy.CoverageCheck.Reason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each rule is a pure function of the context, so each is tested with plain objects, no Spring. */
class ValidationRulesTest {

    private static final LocalDate INCIDENT = LocalDate.of(2026, 3, 10);
    private static final Instant REPORTED = Instant.parse("2026-03-11T09:00:00Z");

    private static ClaimSubmitted claim(String amount) {
        return new ClaimSubmitted(UUID.randomUUID(), "CLM-2026-000001", UUID.randomUUID(), "COLLISION",
                INCIDENT, new BigDecimal(amount));
    }

    private static CoverageCheck coverage(Reason reason, String limit, String deductible) {
        return new CoverageCheck(UUID.randomUUID(), "POL-2026-000042", "COLLISION", INCIDENT,
                reason == Reason.COVERED, reason,
                limit == null ? null : new BigDecimal(limit), deductible == null ? null : new BigDecimal(deductible));
    }

    private static ValidationContext ctx(String amount, Reason reason, String limit, String deductible) {
        return new ValidationContext(claim(amount), coverage(reason, limit, deductible), REPORTED);
    }

    @Nested
    class PolicyInForce {
        private final PolicyInForceRule rule = new PolicyInForceRule();

        @Test
        void passesWhenCovered() {
            assertThat(rule.evaluate(ctx("1000", Reason.COVERED, "5000", "100")).outcome())
                    .isEqualTo(RuleResult.Outcome.PASS);
        }

        @Test
        void failsWhenCancelled() {
            RuleResult r = rule.evaluate(ctx("1000", Reason.POLICY_CANCELLED, "5000", "100"));
            assertThat(r.outcome()).isEqualTo(RuleResult.Outcome.FAIL);
            assertThat(r.message()).contains("cancelled");
        }

        @Test
        void failsWhenOutsidePeriod() {
            RuleResult r = rule.evaluate(ctx("1000", Reason.OUTSIDE_POLICY_PERIOD, "5000", "100"));
            assertThat(r.outcome()).isEqualTo(RuleResult.Outcome.FAIL);
            assertThat(r.message()).contains("outside the period");
        }
    }

    @Nested
    class CoverageOnPolicy {
        private final CoverageOnPolicyRule rule = new CoverageOnPolicyRule();

        @Test
        void failsWhenLossTypeNotCovered() {
            RuleResult r = rule.evaluate(ctx("1000", Reason.COVERAGE_NOT_ON_POLICY, null, null));
            assertThat(r.outcome()).isEqualTo(RuleResult.Outcome.FAIL);
            assertThat(r.message()).contains("no COLLISION coverage");
        }
    }

    @Nested
    class AboveDeductible {
        private final AmountAboveDeductibleRule rule = new AmountAboveDeductibleRule();

        @ParameterizedTest(name = "claimed {0} vs deductible 20000 -> {1}")
        @CsvSource({"19999.99, FAIL", "20000.00, FAIL", "20000.01, PASS", "200000, PASS"})
        void boundaryAtTheDeductible(String claimed, RuleResult.Outcome expected) {
            assertThat(rule.evaluate(ctx(claimed, Reason.COVERED, "500000", "20000")).outcome()).isEqualTo(expected);
        }

        @Test
        void passesWhenNoCoverageTerms() {
            assertThat(rule.evaluate(ctx("10", Reason.COVERAGE_NOT_ON_POLICY, null, null)).outcome())
                    .isEqualTo(RuleResult.Outcome.PASS);   // reported by CoverageOnPolicyRule instead
        }
    }

    @Nested
    class CoverageLimit {
        private final CoverageLimitRule rule = new CoverageLimitRule();

        @ParameterizedTest(name = "claimed {0}, limit 500000, deductible 20000 -> {1}")
        @CsvSource({"520000.00, PASS", "520000.01, WARN", "900000, WARN"})
        void warnsOnlyAboveLimitPlusDeductible(String claimed, RuleResult.Outcome expected) {
            assertThat(rule.evaluate(ctx(claimed, Reason.COVERED, "500000", "20000")).outcome()).isEqualTo(expected);
        }

        @Test
        void warningMentionsCap() {
            assertThat(rule.evaluate(ctx("900000", Reason.COVERED, "500000", "20000")).message())
                    .contains("880000").contains("capped");
        }
    }

    @Nested
    class LateNotification {
        private final LateNotificationRule rule = new LateNotificationRule(30);

        @ParameterizedTest(name = "reported {0} -> {1}")
        @CsvSource({"2026-04-09T10:00:00Z, PASS", "2026-04-10T10:00:00Z, WARN"})   // 30 vs 31 days after 10 Mar
        void warnsAfterThirtyDays(String reportedAt, RuleResult.Outcome expected) {
            ValidationContext c = new ValidationContext(claim("1000"), coverage(Reason.COVERED, "5000", "100"),
                    Instant.parse(reportedAt));
            assertThat(rule.evaluate(c).outcome()).isEqualTo(expected);
        }
    }
}
