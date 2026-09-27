package com.claimflow.claim.claim;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    Optional<Claim> findByIdempotencyKey(String idempotencyKey);

    // The representative query from the design doc: claims of a policy in a status, newest first.
    // Phase 7 adds the composite index (policy_id, status, created_at DESC) and measures it.
    Page<Claim> findByPolicyIdAndStatus(UUID policyId, ClaimStatus status, Pageable pageable);

    Page<Claim> findByPolicyId(UUID policyId, Pageable pageable);
}
