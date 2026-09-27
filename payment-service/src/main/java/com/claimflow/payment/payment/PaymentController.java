package com.claimflow.payment.payment;

import com.claimflow.common.error.ResourceNotFoundException;
import com.claimflow.common.openapi.ApiErrors;
import com.claimflow.payment.settlement.Settlement;
import com.claimflow.payment.settlement.SettlementRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only: payments are created by events, never by API calls. */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    public record SettlementView(BigDecimal approvedAmount, BigDecimal deductible, BigDecimal coverageLimit,
                                 BigDecimal payableAmount, boolean cappedAtLimit) {
    }

    public record PaymentResponse(UUID id, UUID claimId, String claimNumber, BigDecimal amount, PaymentStatus status,
                                  String gatewayReference, String failureReason, int attempts, Instant createdAt,
                                  Instant completedAt, SettlementView settlement) {
    }

    private final PaymentRepository payments;
    private final SettlementRepository settlements;

    public PaymentController(PaymentRepository payments, SettlementRepository settlements) {
        this.payments = payments;
        this.settlements = settlements;
    }

    @GetMapping("/{paymentId}")
    @Transactional(readOnly = true)
    @ApiErrors({404})
    @Operation(summary = "Get a payment with its settlement breakdown")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return toResponse(payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId)));
    }

    @GetMapping
    @Transactional(readOnly = true)
    @ApiErrors({404})
    @Operation(summary = "The payment of a claim (at most one per claim)")
    public PaymentResponse forClaim(@RequestParam UUID claimId) {
        return toResponse(payments.findByClaimId(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment for claim", claimId)));
    }

    private PaymentResponse toResponse(Payment p) {
        Settlement s = settlements.findById(p.getSettlementId()).orElseThrow();
        return new PaymentResponse(p.getId(), p.getClaimId(), p.getClaimNumber(), p.getAmount(), p.getStatus(),
                p.getGatewayReference(), p.getFailureReason(), p.getAttempts(), p.getCreatedAt(), p.getCompletedAt(),
                new SettlementView(s.getApprovedAmount(), s.getDeductible(), s.getCoverageLimit(),
                        s.getPayableAmount(), s.isCappedAtLimit()));
    }
}
