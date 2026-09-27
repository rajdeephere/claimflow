package com.claimflow.claim.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {

    // id breaks ties when two entries share a timestamp (same transaction), keeping insertion order.
    List<ClaimHistory> findByClaimIdOrderByCreatedAtAscIdAsc(UUID claimId);
}
