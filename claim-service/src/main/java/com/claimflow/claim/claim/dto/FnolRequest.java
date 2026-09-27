package com.claimflow.claim.claim.dto;

import com.claimflow.claim.claim.LossType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** First Notice of Loss. */
public record FnolRequest(
        @NotNull UUID policyId,
        @NotNull LossType lossType,
        @NotNull @PastOrPresent LocalDate incidentDate,
        @NotBlank @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal claimedAmount) {
}
