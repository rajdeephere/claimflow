package com.claimflow.claim.claim;

/** Result of a domain operation on a claim; the service turns it into a claim_history row. */
public record StatusChange(ClaimStatus oldStatus, ClaimStatus newStatus, HistoryEventType eventType, String details) {
}
