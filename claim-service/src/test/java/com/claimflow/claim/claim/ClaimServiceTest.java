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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-11T09:00:00Z");

    @Mock
    ClaimRepository claims;
    @Mock
    ClaimHistoryRepository history;
    @Mock
    AdjusterRepository adjusters;
    @Mock
    ClaimNumberGenerator claimNumbers;
    @Mock
    PlatformTransactionManager txManager;   // TransactionTemplate just calls through to the mock

    ClaimService service;

    @BeforeEach
    void setUp() {
        service = new ClaimService(claims, history, adjusters, claimNumbers, txManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MDC.put(CorrelationId.MDC_KEY, "corr-123");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static FnolRequest fnol() {
        return new FnolRequest(UUID.randomUUID(), LossType.COLLISION, LocalDate.of(2026, 3, 10),
                "Rear-ended", new BigDecimal("200000.00"));
    }

    private static Claim existingClaim() {
        return new Claim("CLM-2026-000009", UUID.randomUUID(), LossType.THEFT, LocalDate.of(2026, 2, 1), NOW,
                "Bike stolen", new BigDecimal("50000.00"), "key-1");
    }

    @Test
    void fnolCreatesClaimAndWritesCreationHistoryWithActorAndCorrelationId() {
        when(claimNumbers.next()).thenReturn("CLM-2026-000001");

        ClaimService.FnolResult result = service.fileFnol(fnol(), null, "agent-7");

        assertThat(result.created()).isTrue();
        assertThat(result.claim().getReportedAt()).isEqualTo(NOW);   // from the injected clock
        ArgumentCaptor<ClaimHistory> row = ArgumentCaptor.forClass(ClaimHistory.class);
        verify(history).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(HistoryEventType.CLAIM_CREATED);
        assertThat(row.getValue().getPerformedBy()).isEqualTo("agent-7");
        assertThat(row.getValue().getCorrelationId()).isEqualTo("corr-123");
    }

    @Test
    void repeatedIdempotencyKeyReturnsExistingClaimWithoutCreating() {
        Claim existing = existingClaim();
        when(claims.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        ClaimService.FnolResult result = service.fileFnol(fnol(), "key-1", "agent-7");

        assertThat(result.created()).isFalse();
        assertThat(result.claim()).isSameAs(existing);
        verify(claims, never()).saveAndFlush(any());
        verify(claimNumbers, never()).next();
        verify(history, never()).save(any());
    }

    @Test
    void approvalViaServiceRecordsHistory() {
        Claim claim = existingClaim();
        claim.transitionTo(ClaimStatus.UNDER_REVIEW, TransitionSource.SYSTEM, "ok");
        claim.assignAdjuster(UUID.randomUUID());
        when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));

        service.updateStatus(claim.getId(),
                new UpdateStatusRequest(ClaimStatus.APPROVED, new BigDecimal("45000.00"), null), "adj-1");

        ArgumentCaptor<ClaimHistory> row = ArgumentCaptor.forClass(ClaimHistory.class);
        verify(history).save(row.capture());
        assertThat(row.getValue().getOldStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(row.getValue().getNewStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(row.getValue().getPerformedBy()).isEqualTo("adj-1");
    }

    @Test
    void failedTransitionWritesNoHistory() {
        Claim claim = existingClaim();   // SUBMITTED
        when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.updateStatus(claim.getId(),
                new UpdateStatusRequest(ClaimStatus.CLOSED, null, null), "adj-1"))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(history, never()).save(any());
    }

    @Test
    void systemTransitionIsRecordedAsSystem() {
        Claim claim = existingClaim();
        when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));

        service.applySystemTransition(claim.getId(), ClaimStatus.UNDER_REVIEW, "Policy in force, coverage OK");

        ArgumentCaptor<ClaimHistory> row = ArgumentCaptor.forClass(ClaimHistory.class);
        verify(history).save(row.capture());
        assertThat(row.getValue().getPerformedBy()).isEqualTo(ClaimService.SYSTEM_ACTOR);
    }

    @Test
    void cannotAssignInactiveOrUnknownAdjuster() {
        Claim claim = existingClaim();
        UUID unknown = UUID.randomUUID();
        when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(adjusters.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignAdjuster(claim.getId(), unknown, "mgr"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void assignsActiveAdjuster() {
        Claim claim = existingClaim();
        Adjuster adjuster = new Adjuster("Ravi Kumar", "ravi@example.com");
        when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(adjusters.findById(adjuster.getId())).thenReturn(Optional.of(adjuster));

        service.assignAdjuster(claim.getId(), adjuster.getId(), "mgr");

        assertThat(claim.getAdjusterId()).isEqualTo(adjuster.getId());
    }

    @Test
    void historyOfUnknownClaimIsNotFound() {
        UUID id = UUID.randomUUID();
        when(claims.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.history(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
