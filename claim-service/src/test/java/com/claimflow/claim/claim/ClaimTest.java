package com.claimflow.claim.claim;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ConflictException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimTest {

    private static Claim newClaim() {
        return new Claim("CLM-2026-000001", UUID.randomUUID(), LossType.COLLISION, LocalDate.of(2026, 3, 10),
                Instant.parse("2026-03-11T09:00:00Z"), "Rear-ended at a signal", new BigDecimal("200000.00"), null);
    }

    private static Claim underReviewWithAdjuster() {
        Claim claim = newClaim();
        claim.transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.SYSTEM, "validated");
        claim.assignAdjuster(UUID.randomUUID());
        return claim;
    }

    @Test
    void fnolStartsSubmittedAndRecordsCreation() {
        Claim claim = newClaim();

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        StatusChange creation = claim.creation();
        assertThat(creation.oldStatus()).isNull();
        assertThat(creation.eventType()).isEqualTo(HistoryEventType.CLAIM_CREATED);
    }

    @Nested
    class Transitions {

        @Test
        void validTransitionReturnsChangeWithEventType() {
            StatusChange change = newClaim().transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.SYSTEM, "ok");

            assertThat(change.oldStatus()).isEqualTo(ClaimStatus.SUBMITTED);
            assertThat(change.newStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
            assertThat(change.eventType()).isEqualTo(HistoryEventType.CLAIM_VALIDATED);
        }

        @Test
        void invalidTransitionIsConflictAndLeavesStatusUnchanged() {
            Claim claim = newClaim();

            assertThatThrownBy(() -> claim.transitionTo(ClaimStatus.SETTLED, TransitionSource.SYSTEM, null))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("cannot move from SUBMITTED to SETTLED");
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        }

        @Test
        void userCannotTriggerSystemOnlyTransition() {
            Claim claim = newClaim();

            assertThatThrownBy(() -> claim.transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.USER, null))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("set automatically by the system");
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        }

        @Test
        void closedClaimCannotBeApproved() {
            Claim claim = newClaim();
            claim.reject("Policy lapsed", TransitionSource.SYSTEM);
            claim.close();

            // Transition check comes first: 409 "final state", not 422 "no adjuster".
            assertThatThrownBy(() -> claim.approve(new BigDecimal("1000")))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("CLOSED is a final state");
        }
    }

    @Nested
    class Approval {

        @Test
        void approvesWithinClaimedAmount() {
            Claim claim = underReviewWithAdjuster();

            claim.approve(new BigDecimal("180000.00"));

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(claim.getApprovedAmount()).isEqualByComparingTo("180000");
        }

        @Test
        void approvalRequiresAdjuster() {
            Claim claim = newClaim();
            claim.transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.SYSTEM, "validated");

            assertThatThrownBy(() -> claim.approve(new BigDecimal("1000")))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("must have an adjuster");
        }

        @Test
        void cannotApproveMoreThanClaimed() {
            assertThatThrownBy(() -> underReviewWithAdjuster().approve(new BigDecimal("200000.01")))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("exceeds claimed amount");
        }

        @Test
        void approvalMustExceedValidatedDeductible() {
            Claim claim = newClaim();
            claim.markValidated(new BigDecimal("500000.00"), new BigDecimal("20000.00"), "validated");
            claim.assignAdjuster(UUID.randomUUID());

            assertThatThrownBy(() -> claim.approve(new BigDecimal("20000.00")))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("nothing would be payable");
            claim.approve(new BigDecimal("20000.01"));   // one paisa above is payable
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        }

        @Test
        void markValidatedStoresCoverageTerms() {
            Claim claim = newClaim();

            StatusChange change = claim.markValidated(new BigDecimal("500000.00"), new BigDecimal("20000.00"), "ok");

            assertThat(change.newStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
            assertThat(claim.getCoverageLimit()).isEqualTo(new BigDecimal("500000.00"));
            assertThat(claim.getDeductible()).isEqualTo(new BigDecimal("20000.00"));
        }

        @Test
        void approvalAmountIsRequired() {
            assertThatThrownBy(() -> underReviewWithAdjuster().approve(null))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }

    @Nested
    class Rejection {

        @Test
        void rejectionRequiresReason() {
            Claim claim = underReviewWithAdjuster();

            assertThatThrownBy(() -> claim.reject("  ", TransitionSource.USER))
                    .isInstanceOf(BusinessRuleException.class);
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        }

        @Test
        void rejectionStoresReason() {
            Claim claim = underReviewWithAdjuster();

            claim.reject("Damage pre-dates policy", TransitionSource.USER);

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
            assertThat(claim.getRejectionReason()).isEqualTo("Damage pre-dates policy");
        }
    }

    @Nested
    class AdjusterAssignment {

        @Test
        void reassignmentIsRecordedWithoutStatusChange() {
            Claim claim = newClaim();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            claim.assignAdjuster(first);

            StatusChange change = claim.assignAdjuster(second);

            assertThat(change.oldStatus()).isEqualTo(change.newStatus());
            assertThat(change.details()).contains("Reassigned").contains(first.toString());
            assertThat(claim.getAdjusterId()).isEqualTo(second);
        }

        @Test
        void cannotAssignAfterDecision() {
            Claim claim = underReviewWithAdjuster();
            claim.approve(new BigDecimal("1000"));

            assertThatThrownBy(() -> claim.assignAdjuster(UUID.randomUUID()))
                    .isInstanceOf(ConflictException.class);
        }
    }
}
