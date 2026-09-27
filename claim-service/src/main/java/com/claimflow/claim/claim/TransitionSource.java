package com.claimflow.claim.claim;

/** Who is allowed to trigger a transition. */
public enum TransitionSource {
    /** A person, through the REST API (adjuster, claims manager). */
    USER,
    /** Another service, through an event (validation result, payment outcome). */
    SYSTEM
}
