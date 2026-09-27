package com.claimflow.payment.payment;

import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents;
import com.claimflow.payment.gateway.GatewayUnavailableException;
import com.claimflow.payment.gateway.PaymentGateway;
import com.claimflow.payment.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorTest {

    private static final Instant NOW = Instant.parse("2026-03-20T10:00:00Z");

    @Mock
    PaymentRepository payments;
    @Mock
    PaymentGateway gateway;
    @Mock
    OutboxWriter outbox;
    @Mock
    PlatformTransactionManager txManager;

    PaymentProcessor processor;
    Payment payment;

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(payments, gateway, outbox, txManager, Clock.fixed(NOW, ZoneOffset.UTC), 50, 3);
        payment = new Payment(UUID.randomUUID(), "CLM-2026-000001", UUID.randomUUID(), new BigDecimal("180000.00"),
                "corr-pay");
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    @Test
    void successCompletesPaymentAndAnnouncesIt() {
        when(gateway.transfer(payment.getId(), "CLM-2026-000001", new BigDecimal("180000.00")))
                .thenReturn(new PaymentGateway.Success("TRF-1"));

        processor.process(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getGatewayReference()).isEqualTo("TRF-1");
        assertThat(payment.getCompletedAt()).isEqualTo(NOW);
        ArgumentCaptor<ClaimEvents.PaymentCompleted> event = ArgumentCaptor.forClass(ClaimEvents.PaymentCompleted.class);
        verify(outbox).append(eq(Topics.PAYMENT_EVENTS), eq(EventTypes.PAYMENT_COMPLETED), eq(payment.getClaimId()),
                event.capture());
        assertThat(event.getValue().amount()).isEqualTo(new BigDecimal("180000.00"));
    }

    @Test
    void usesPaymentIdAsIdempotencyKey() {
        when(gateway.transfer(any(), anyString(), any())).thenReturn(new PaymentGateway.Success("TRF-1"));

        processor.process(payment.getId());

        verify(gateway).transfer(eq(payment.getId()), anyString(), any());
    }

    @Test
    void declineFailsPaymentAndAnnouncesIt() {
        when(gateway.transfer(any(), anyString(), any())).thenReturn(new PaymentGateway.Declined("Account closed"));

        processor.process(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).contains("Account closed");
        verify(outbox).append(eq(Topics.PAYMENT_EVENTS), eq(EventTypes.PAYMENT_FAILED), eq(payment.getClaimId()),
                any(ClaimEvents.PaymentFailed.class));
    }

    @Test
    void gatewayOutageKeepsPaymentInitiatedUntilMaxAttempts() {
        when(gateway.transfer(any(), anyString(), any())).thenThrow(new GatewayUnavailableException("timeout"));

        processor.process(payment.getId());
        processor.process(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);   // 2 of 3 attempts: keep trying
        assertThat(payment.getAttempts()).isEqualTo(2);
        verify(outbox, never()).append(anyString(), anyString(), any(), any());

        processor.process(payment.getId());   // 3rd attempt -> give up, manual review

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).contains("unavailable after 3 attempts");
        verify(outbox).append(eq(Topics.PAYMENT_EVENTS), eq(EventTypes.PAYMENT_FAILED), any(), any());
    }

    @Test
    void alreadyCompletedPaymentIsNotSentToTheBankAgain() {
        payment.complete("TRF-OLD", NOW);

        processor.process(payment.getId());

        verifyNoInteractions(gateway);
        verifyNoInteractions(outbox);
    }
}
