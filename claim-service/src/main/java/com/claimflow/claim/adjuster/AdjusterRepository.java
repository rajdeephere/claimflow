package com.claimflow.claim.adjuster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdjusterRepository extends JpaRepository<Adjuster, UUID> {

    boolean existsByEmail(String email);

    List<Adjuster> findByActiveTrueOrderByName();
}
