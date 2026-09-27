package com.claimflow.policy.policy;

/**
 * Only states that need an explicit action are stored. "Expired" or "not yet started" are derived
 * from the dates (see {@link Policy#isInForceOn}), so no nightly job has to flip a status column.
 */
public enum PolicyStatus {
    ACTIVE,
    CANCELLED
}
