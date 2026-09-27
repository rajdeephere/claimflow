package com.claimflow.validation;

import java.math.BigDecimal;
import java.util.List;

public record ValidationOutcome(boolean valid, BigDecimal coverageLimit, BigDecimal deductible,
                                List<String> reasons, List<String> warnings) {

    public static ValidationOutcome passed(BigDecimal coverageLimit, BigDecimal deductible, List<String> warnings) {
        return new ValidationOutcome(true, coverageLimit, deductible, List.of(), List.copyOf(warnings));
    }

    public static ValidationOutcome failed(List<String> reasons) {
        return new ValidationOutcome(false, null, null, List.copyOf(reasons), List.of());
    }
}
