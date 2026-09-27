package com.claimflow.claim.claim;

import com.claimflow.common.error.ConflictException;

/** A transition not in the lifecycle table, e.g. CLOSED -> APPROVED. Maps to 409 Conflict. */
public class InvalidStateTransitionException extends ConflictException {

    public InvalidStateTransitionException(String claimNumber, ClaimStatus from, ClaimStatus to) {
        super("Claim " + claimNumber + " cannot move from " + from + " to " + to
                + (from.isTerminal() ? " (" + from + " is a final state)" : "; allowed: " + from.allowedNext()));
    }
}
