package com.claimflow.policy.policy.dto;

import com.claimflow.policy.policy.ProductType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreatePolicyRequest(
        @NotNull UUID customerId,
        @NotNull ProductType productType,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal premium,
        @NotEmpty List<@Valid @NotNull CoverageRequest> coverages) {

    // Cross-field rule: reported as field "endDateAfterStartDate" in the 400 response.
    // Null dates are left to @NotNull so the client gets one clear message per problem.
    @JsonIgnore
    @AssertTrue(message = "endDate must be after startDate")
    public boolean isEndDateAfterStartDate() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
