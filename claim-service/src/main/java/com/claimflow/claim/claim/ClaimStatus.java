package com.claimflow.claim.claim;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.claimflow.claim.claim.TransitionSource.SYSTEM;
import static com.claimflow.claim.claim.TransitionSource.USER;

/**
 * Claim lifecycle as a state machine (ADR-0008). The transition table below is the single source
 * of truth: anything not listed is invalid.
 *
 * <pre>
 *  (FNOL) -> SUBMITTED --validation ok--> UNDER_REVIEW --adjuster--> APPROVED --> SETTLEMENT_PENDING
 *               |                            |                                          |
 *               +--validation failed--+      +--adjuster rejects--+             PAYMENT_INITIATED
 *                                     v                           v                     |
 *                                  REJECTED <---------------------+                  SETTLED
 *                                     |                                                 |
 *                                     +---------------------> CLOSED <------------------+
 * </pre>
 */
public enum ClaimStatus {
    SUBMITTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    SETTLEMENT_PENDING,
    PAYMENT_INITIATED,
    SETTLED,
    CLOSED;

    // from -> (to -> who may trigger it). Built in a static block because an enum constant
    // can't refer to constants declared after it in its own constructor arguments.
    private static final Map<ClaimStatus, Map<ClaimStatus, TransitionSource>> TRANSITIONS =
            new EnumMap<>(ClaimStatus.class);

    static {
        allow(SUBMITTED, UNDER_REVIEW, SYSTEM);          // validation passed
        allow(SUBMITTED, REJECTED, SYSTEM);              // validation failed
        allow(UNDER_REVIEW, APPROVED, USER);             // adjuster approves
        allow(UNDER_REVIEW, REJECTED, USER);             // adjuster rejects
        allow(APPROVED, SETTLEMENT_PENDING, SYSTEM);     // handed to Payment
        allow(SETTLEMENT_PENDING, PAYMENT_INITIATED, SYSTEM);
        allow(PAYMENT_INITIATED, SETTLED, SYSTEM);       // payment completed
        allow(SETTLED, CLOSED, USER);
        allow(REJECTED, CLOSED, USER);
    }

    private static void allow(ClaimStatus from, ClaimStatus to, TransitionSource source) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(ClaimStatus.class)).put(to, source);
    }

    /** Who may move a claim from this status to {@code target}; empty if the transition is invalid. */
    public Optional<TransitionSource> transitionSourceTo(ClaimStatus target) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(this, Map.of()).get(target));
    }

    public boolean canTransitionTo(ClaimStatus target) {
        return transitionSourceTo(target).isPresent();
    }

    public Set<ClaimStatus> allowedNext() {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(this, Map.of()).keySet());
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
