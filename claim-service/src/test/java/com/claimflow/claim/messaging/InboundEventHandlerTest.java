package com.claimflow.claim.messaging;

import com.claimflow.claim.claim.ClaimService;
import com.claimflow.claim.claim.ClaimStatus;
import com.claimflow.common.config.JsonConfig;
import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.payload.ClaimEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboundEventHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-11T09:00:00Z");

    @Mock
    ProcessedEventStore processed;
    @Mock
    ClaimService claims;

    // same money-safe settings as the application's ObjectMapper
    private final ObjectMapper mapper = JsonConfig.configure(new ObjectMapper().registerModule(new JavaTimeModule()));

    private InboundEventHandler handler() {
        return new InboundEventHandler(processed, claims, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EventEnvelope envelope(String type, UUID claimId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, NOW, "test", claimId, "corr-1",
                mapper.valueToTree(payload));
    }

    @Test
    void validatedMovesClaimToUnderReview() throws Exception {
        UUID claimId = UUID.randomUUID();
        EventEnvelope event = envelope(EventTypes.CLAIM_VALIDATED, claimId,
                new ClaimEvents.ClaimValidated(claimId, new BigDecimal("500000"), new BigDecimal("20000")));
        when(processed.markProcessed(event.eventId(), InboundEventHandler.CONSUMER, event.eventType(), NOW))
                .thenReturn(true);

        handler().handle(event);

        verify(claims).applySystemTransition(eq(claimId), eq(ClaimStatus.UNDER_REVIEW),
                eq("Validated: coverage limit 500000, deductible 20000"));
    }

    @Test
    void validationFailureRejectsWithAllReasons() throws Exception {
        UUID claimId = UUID.randomUUID();
        EventEnvelope event = envelope(EventTypes.CLAIM_VALIDATION_FAILED, claimId,
                new ClaimEvents.ClaimValidationFailed(claimId, List.of("Policy cancelled", "Coverage missing")));
        when(processed.markProcessed(any(), anyString(), anyString(), any())).thenReturn(true);

        handler().handle(event);

        verify(claims).applySystemTransition(eq(claimId), eq(ClaimStatus.REJECTED),
                eq("Validation failed: Policy cancelled; Coverage missing"));
    }

    @Test
    void duplicateEventIsSkipped() throws Exception {
        UUID claimId = UUID.randomUUID();
        EventEnvelope event = envelope(EventTypes.PAYMENT_COMPLETED, claimId,
                new ClaimEvents.PaymentCompleted(claimId, UUID.randomUUID(), BigDecimal.TEN));
        when(processed.markProcessed(any(), anyString(), anyString(), any())).thenReturn(false);

        handler().handle(event);

        verifyNoInteractions(claims);
    }

    @Test
    void unknownEventTypeIsIgnoredNotFailed() throws Exception {
        EventEnvelope event = envelope("ClaimTeleported", UUID.randomUUID(), java.util.Map.of("x", 1));
        when(processed.markProcessed(any(), anyString(), anyString(), any())).thenReturn(true);

        handler().handle(event);   // no exception: tolerant reader

        verifyNoInteractions(claims);
    }

    @Test
    void paymentFailedIsRecordedNotTransitioned() throws Exception {
        UUID claimId = UUID.randomUUID();
        EventEnvelope event = envelope(EventTypes.PAYMENT_FAILED, claimId,
                new ClaimEvents.PaymentFailed(claimId, UUID.randomUUID(), "Account closed"));
        when(processed.markProcessed(any(), anyString(), anyString(), any())).thenReturn(true);

        handler().handle(event);

        verify(claims).recordPaymentFailure(eq(claimId), contains("Account closed"));
    }
}
