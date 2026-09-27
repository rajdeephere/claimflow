package com.claimflow.policy.policy;

import java.util.EnumSet;
import java.util.Set;

import static com.claimflow.policy.policy.CoverageType.COLLISION;
import static com.claimflow.policy.policy.CoverageType.FIRE;
import static com.claimflow.policy.policy.CoverageType.FLOOD;
import static com.claimflow.policy.policy.CoverageType.HOSPITALIZATION;
import static com.claimflow.policy.policy.CoverageType.THEFT;
import static com.claimflow.policy.policy.CoverageType.THIRD_PARTY_LIABILITY;

/** Line of business. Each product only offers the coverages that make sense for it. */
public enum ProductType {
    MOTOR(EnumSet.of(COLLISION, THEFT, THIRD_PARTY_LIABILITY)),
    HOME(EnumSet.of(FIRE, FLOOD, THEFT)),
    HEALTH(EnumSet.of(HOSPITALIZATION));

    private final Set<CoverageType> allowedCoverages;

    ProductType(Set<CoverageType> allowedCoverages) {
        this.allowedCoverages = allowedCoverages;
    }

    public boolean allows(CoverageType coverageType) {
        return allowedCoverages.contains(coverageType);
    }

    public Set<CoverageType> allowedCoverages() {
        return EnumSet.copyOf(allowedCoverages);
    }
}
