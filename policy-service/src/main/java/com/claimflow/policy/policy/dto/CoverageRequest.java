package com.claimflow.policy.policy.dto;

import com.claimflow.policy.policy.CoverageType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CoverageRequest(
        @NotNull CoverageType coverageType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal limitAmount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal deductible) {
}
