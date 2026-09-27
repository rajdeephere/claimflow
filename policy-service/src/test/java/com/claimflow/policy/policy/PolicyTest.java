package com.claimflow.policy.policy;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ConflictException;
import com.claimflow.policy.customer.Customer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure domain tests: no Spring, no DB, milliseconds to run. */
class PolicyTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    private final Customer customer =
            new Customer("Asha", "Rao", "asha@example.com", null, LocalDate.of(1990, 5, 1));

    private Policy motorPolicy() {
        return new Policy("POL-2026-000001", customer, ProductType.MOTOR, START, END, new BigDecimal("12000.00"));
    }

    @Test
    void newPolicyIsActive() {
        assertThat(motorPolicy().getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void endDateMustBeAfterStartDate() {
        assertThatThrownBy(() -> new Policy("P", customer, ProductType.MOTOR, END, START, BigDecimal.TEN))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Nested
    class InForce {

        // Boundaries are where off-by-one bugs live: both ends of the period are inclusive.
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "2025-12-31, false",
                "2026-01-01, true",
                "2026-06-15, true",
                "2026-12-31, true",
                "2027-01-01, false"
        })
        void inForceOnlyWithinPolicyPeriod(LocalDate date, boolean expected) {
            assertThat(motorPolicy().isInForceOn(date)).isEqualTo(expected);
        }

        @Test
        void cancelledPolicyIsNeverInForce() {
            Policy policy = motorPolicy();
            policy.cancel();
            assertThat(policy.isInForceOn(LocalDate.of(2026, 6, 15))).isFalse();
        }
    }

    @Nested
    class Coverages {

        @Test
        void addsAllowedCoverage() {
            Policy policy = motorPolicy();
            policy.addCoverage(CoverageType.COLLISION, new BigDecimal("500000.00"), new BigDecimal("20000.00"));

            assertThat(policy.findCoverage(CoverageType.COLLISION)).isPresent();
            assertThat(policy.findCoverage(CoverageType.THEFT)).isEmpty();
        }

        @Test
        void rejectsCoverageNotOfferedByProduct() {
            assertThatThrownBy(() -> motorPolicy().addCoverage(CoverageType.FLOOD, BigDecimal.TEN, BigDecimal.ONE))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("FLOOD is not offered for MOTOR");
        }

        @Test
        void rejectsDuplicateCoverageType() {
            Policy policy = motorPolicy();
            policy.addCoverage(CoverageType.THEFT, new BigDecimal("100000"), new BigDecimal("5000"));

            assertThatThrownBy(() -> policy.addCoverage(CoverageType.THEFT, new BigDecimal("200000"), BigDecimal.ONE))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test
        void rejectsDeductibleNotBelowLimit() {
            assertThatThrownBy(() -> motorPolicy().addCoverage(CoverageType.COLLISION,
                    new BigDecimal("1000.00"), new BigDecimal("1000.00")))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void coverageListCannotBeModifiedFromOutside() {
            assertThatThrownBy(() -> motorPolicy().getCoverages().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void cancellingTwiceIsAConflict() {
        Policy policy = motorPolicy();
        policy.cancel();
        assertThatThrownBy(policy::cancel).isInstanceOf(ConflictException.class);
    }
}
