package com.claimflow.payment.payment;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByClaimId(UUID claimId);

    boolean existsByClaimId(UUID claimId);

    @Query("SELECT p.id FROM Payment p WHERE p.status = com.claimflow.payment.payment.PaymentStatus.INITIATED "
            + "ORDER BY p.createdAt")
    List<UUID> findInitiatedIds(Limit limit);
}
