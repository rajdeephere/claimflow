package com.claimflow.payment.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findByClaimId(UUID claimId);
}
