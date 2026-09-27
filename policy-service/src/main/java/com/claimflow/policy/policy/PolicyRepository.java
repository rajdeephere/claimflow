package com.claimflow.policy.policy;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    // Load coverages in the same query. open-in-view is off, so lazy loading after the
    // transaction would fail, and loading them one policy at a time would be N+1 queries.
    @EntityGraph(attributePaths = {"coverages", "customer"})
    Optional<Policy> findWithCoveragesById(UUID id);

    @EntityGraph(attributePaths = {"coverages", "customer"})
    List<Policy> findByCustomerIdOrderByStartDateDesc(UUID customerId);
}
