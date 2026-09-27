package com.claimflow.payment.settlement;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * payable = min(approved - deductible, coverageLimit), in BigDecimal with scale 2 and HALF_EVEN rounding.
 *
 * <pre>
 *   Coverage limit 5,00,000 | Approved 2,00,000 | Deductible 20,000  ->  Settlement 1,80,000
 *   Coverage limit 5,00,000 | Approved 9,00,000 | Deductible 20,000  ->  Settlement 5,00,000 (capped)
 * </pre>
 * Pure function: no Spring dependencies, no I/O.
 */
@Component
public class SettlementCalculator {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;   // banker's rounding: no systematic bias

    public record Result(BigDecimal payable, boolean cappedAtLimit) {
    }

    public Result calculate(BigDecimal approvedAmount, BigDecimal deductible, BigDecimal coverageLimit) {
        requirePositive(approvedAmount, "approved amount");
        requirePositive(coverageLimit, "coverage limit");
        if (deductible == null || deductible.signum() < 0) {
            throw new IllegalArgumentException("Deductible must be zero or more: " + deductible);
        }

        BigDecimal afterDeductible = approvedAmount.subtract(deductible).setScale(SCALE, ROUNDING);
        if (afterDeductible.signum() <= 0) {
            // Claim Service refuses such approvals (Phase 6), so reaching here means a bad event: non-retryable, DLT.
            throw new IllegalArgumentException("Nothing payable: approved " + approvedAmount
                    + " does not exceed deductible " + deductible);
        }
        BigDecimal limit = coverageLimit.setScale(SCALE, ROUNDING);
        boolean capped = afterDeductible.compareTo(limit) > 0;
        return new Result(capped ? limit : afterDeductible, capped);
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("The " + name + " must be greater than zero: " + value);
        }
    }
}
