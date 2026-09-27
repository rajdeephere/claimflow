package com.claimflow.claim.claim.dto;

import com.claimflow.claim.claim.ClaimStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PATCH /claims/{id}/status. Which extra fields are required depends on the target
 * (APPROVED needs approvedAmount, REJECTED needs reason); the domain enforces that (422).
 */
public record UpdateStatusRequest(
        @NotNull ClaimStatus targetStatus,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal approvedAmount,
        @Size(max = 500) String reason) {
}
