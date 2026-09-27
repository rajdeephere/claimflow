package com.claimflow.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Normalises monetary amounts to scale 2 where they enter the domain (BUG-017).
 *
 * Without this, an amount keeps whatever scale the client sent (200000, 200000.0) until it's read back
 * from the NUMERIC(15,2) column, so the same resource showed "500000.0" in the create response and
 * "500000.00" on the next GET, and events carried the client's scale to other services.
 */
public final class Money {

    public static final int SCALE = 2;

    private Money() {
    }

    /**
     * @return the amount with exactly two decimals, or null for null
     * @throws ArithmeticException if it has more than two significant decimals. Request validation
     *         (@Digits(fraction = 2)) rejects those first, so reaching this is a programming error.
     */
    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
