package com.claimflow.claim.claim;

import com.claimflow.claim.adjuster.Adjuster;
import com.claimflow.claim.adjuster.AdjusterRepository;
import com.claimflow.claim.claim.dto.FnolRequest;
import com.claimflow.claim.claim.dto.UpdateStatusRequest;
import com.claimflow.claim.history.ClaimHistory;
import com.claimflow.claim.history.ClaimHistoryRepository;
import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClaimService {

    public static final String SYSTEM_ACTOR = "system";

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    /** FNOL outcome: {@code created == false} means an idempotent replay of an earlier request. */
    public record FnolResult(Claim claim, boolean created) {
    }

    private final ClaimRepository claims;
    private final ClaimHistoryRepository history;
    private final AdjusterRepository adjusters;
    private final ClaimNumberGenerator claimNumbers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ClaimService(ClaimRepository claims, ClaimHistoryRepository history, AdjusterRepository adjusters,
                        ClaimNumberGenerator claimNumbers, PlatformTransactionManager txManager, Clock clock) {
        this.claims = claims;
        this.history = history;
        this.adjusters = adjusters;
        this.claimNumbers = claimNumbers;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ---- FNOL ----

    /**
     * Not @Transactional on purpose: if two retries with the same Idempotency-Key race, the loser's
     * insert hits the unique constraint, which marks its transaction rollback-only. The winner's claim
     * must then be read in a NEW transaction, so each step gets its own via TransactionTemplate.
     */
    public FnolResult fileFnol(FnolRequest req, String idempotencyKey, String actor) {
        if (idempotencyKey != null) {
            Optional<Claim> existing = tx.execute(s -> claims.findByIdempotencyKey(idempotencyKey));
            if (existing.isPresent()) {
                log.info("Idempotent replay of FNOL key {} -> claim {}", idempotencyKey,
                        existing.get().getClaimNumber());
                return new FnolResult(existing.get(), false);
            }
        }
        try {
            return new FnolResult(tx.execute(s -> createClaim(req, idempotencyKey, actor)), true);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            Claim winner = tx.execute(s -> claims.findByIdempotencyKey(idempotencyKey)).orElseThrow(() -> e);
            log.info("Concurrent FNOL with key {} resolved to claim {}", idempotencyKey, winner.getClaimNumber());
            return new FnolResult(winner, false);
        }
    }

    private Claim createClaim(FnolRequest req, String idempotencyKey, String actor) {
        // The policy is NOT checked here: FNOL must never be lost because Policy Service is slow or down.
        // Validation happens asynchronously after ClaimSubmitted (Phases 4-5).
        Claim claim = new Claim(claimNumbers.next(), req.policyId(), req.lossType(), req.incidentDate(),
                Instant.now(clock), req.description(), req.claimedAmount(), idempotencyKey);
        claims.saveAndFlush(claim);   // flush now so a duplicate key fails inside this transaction
        record(claim, claim.creation(), actor);
        log.info("FNOL {} filed for policy {} ({}, {})", claim.getClaimNumber(), claim.getPolicyId(),
                claim.getLossType(), claim.getClaimedAmount());
        return claim;
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public Claim get(UUID claimId) {
        return claims.findById(claimId).orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));
    }

    @Transactional(readOnly = true)
    public Page<Claim> listForPolicy(UUID policyId, ClaimStatus status, int page, int size) {
        // Sort is fixed server-side: letting clients sort by any property risks errors and unindexed scans.
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return status == null
                ? claims.findByPolicyId(policyId, pageable)
                : claims.findByPolicyIdAndStatus(policyId, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<ClaimHistory> history(UUID claimId) {
        if (!claims.existsById(claimId)) {
            throw new ResourceNotFoundException("Claim", claimId);
        }
        return history.findByClaimIdOrderByCreatedAtAscIdAsc(claimId);
    }

    // ---- commands ----

    /** User-requested status change (adjuster / claims manager). */
    @Transactional
    public Claim updateStatus(UUID claimId, UpdateStatusRequest req, String actor) {
        Claim claim = get(claimId);
        StatusChange change = switch (req.targetStatus()) {
            case APPROVED -> claim.approve(req.approvedAmount());
            case REJECTED -> claim.reject(req.reason(), TransitionSource.USER);
            case CLOSED -> claim.close();
            // Anything else is either invalid (409) or system-only (422); the domain decides which.
            default -> claim.transitionTo(req.targetStatus(), TransitionSource.USER, req.reason());
        };
        record(claim, change, actor);
        return claim;
    }

    /** System-driven status change; called by Kafka consumers from Phase 4. */
    @Transactional
    public Claim applySystemTransition(UUID claimId, ClaimStatus target, String details) {
        Claim claim = get(claimId);
        StatusChange change = target == ClaimStatus.REJECTED
                ? claim.reject(details, TransitionSource.SYSTEM)
                : claim.transitionTo(target, TransitionSource.SYSTEM, details);
        record(claim, change, SYSTEM_ACTOR);
        return claim;
    }

    @Transactional
    public Claim assignAdjuster(UUID claimId, UUID adjusterId, String actor) {
        Claim claim = get(claimId);
        Adjuster adjuster = adjusters.findById(adjusterId)
                .orElseThrow(() -> new BusinessRuleException("Adjuster " + adjusterId + " does not exist"));
        if (!adjuster.isActive()) {
            throw new BusinessRuleException("Adjuster " + adjuster.getName() + " is not active");
        }
        record(claim, claim.assignAdjuster(adjusterId), actor);
        return claim;
    }

    // The history row is written in the same transaction as the change: both commit or neither does.
    private void record(Claim claim, StatusChange change, String actor) {
        history.save(new ClaimHistory(claim.getId(), change.eventType(), change.oldStatus(), change.newStatus(),
                actor, CorrelationId.current(), change.details(), Instant.now(clock)));
        // creation (old == null) has its own FNOL log line; reassignment keeps the same status
        if (change.oldStatus() != null && change.oldStatus() != change.newStatus()) {
            log.info("Claim {} {} -> {} by {}", claim.getClaimNumber(), change.oldStatus(), change.newStatus(),
                    actor);
        }
    }
}
