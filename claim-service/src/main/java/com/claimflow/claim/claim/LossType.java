package com.claimflow.claim.claim;

/**
 * What kind of loss is being claimed. Mirrors the Policy Service's coverage types by name; the
 * services share the *contract* (the names on the wire), not a Java class, so each can evolve.
 */
public enum LossType {
    COLLISION,
    THEFT,
    THIRD_PARTY_LIABILITY,
    FIRE,
    FLOOD,
    HOSPITALIZATION
}
