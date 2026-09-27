package com.claimflow.claim.claim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.claimflow.claim.claim.ClaimStatus.APPROVED;
import static com.claimflow.claim.claim.ClaimStatus.CLOSED;
import static com.claimflow.claim.claim.ClaimStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle table is the heart of the Claim Service, so it is tested exhaustively:
 * every one of the 8 x 8 = 64 (from, to) pairs against an independently written expectation.
 */
class ClaimStatusTest {

    // Written out by hand from the design doc, NOT derived from the production table.
    private static final Map<String, TransitionSource> EXPECTED = Map.of(
            "SUBMITTED->UNDER_REVIEW", TransitionSource.SYSTEM,
            "SUBMITTED->REJECTED", TransitionSource.SYSTEM,
            "UNDER_REVIEW->APPROVED", TransitionSource.USER,
            "UNDER_REVIEW->REJECTED", TransitionSource.USER,
            "APPROVED->SETTLEMENT_PENDING", TransitionSource.SYSTEM,
            "SETTLEMENT_PENDING->PAYMENT_INITIATED", TransitionSource.SYSTEM,
            "PAYMENT_INITIATED->SETTLED", TransitionSource.SYSTEM,
            "SETTLED->CLOSED", TransitionSource.USER,
            "REJECTED->CLOSED", TransitionSource.USER);

    static Stream<Arguments> allPairs() {
        return Arrays.stream(ClaimStatus.values())
                .flatMap(from -> Arrays.stream(ClaimStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void transitionTableMatchesDesign(ClaimStatus from, ClaimStatus to) {
        TransitionSource expected = EXPECTED.get(from + "->" + to);

        assertThat(from.transitionSourceTo(to).orElse(null)).isEqualTo(expected);
        assertThat(from.canTransitionTo(to)).isEqualTo(expected != null);
    }

    @Test
    void closedIsTheOnlyTerminalState() {
        assertThat(EnumSet.allOf(ClaimStatus.class).stream().filter(ClaimStatus::isTerminal))
                .containsExactly(CLOSED);
    }

    @Test
    void closedCannotBeReopenedAsApproved() {
        assertThat(CLOSED.canTransitionTo(APPROVED)).isFalse();   // the example called out in the spec
    }

    @Test
    void everyStatusIsReachableFromSubmitted() {
        assertThat(reachableFrom(SUBMITTED)).isEqualTo(EnumSet.allOf(ClaimStatus.class));
    }

    @Test
    void everyStatusCanEventuallyBeClosed() {
        // No dead ends: a claim can never get stuck in a state with no way to CLOSED.
        for (ClaimStatus status : ClaimStatus.values()) {
            assertThat(reachableFrom(status)).as("from %s", status).contains(CLOSED);
        }
    }

    @Test
    void noStatusTransitionsToItself() {
        for (ClaimStatus status : ClaimStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> %s", status, status).isFalse();
        }
    }

    private static Set<ClaimStatus> reachableFrom(ClaimStatus start) {
        Set<ClaimStatus> seen = EnumSet.of(start);
        Deque<ClaimStatus> queue = new ArrayDeque<>(Set.of(start));
        while (!queue.isEmpty()) {
            for (ClaimStatus next : queue.poll().allowedNext()) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}
