package com.claimflow.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void anyInputScaleBecomesTwoDecimals() {
        assertThat(Money.of(new BigDecimal("500000"))).isEqualTo(new BigDecimal("500000.00"));
        assertThat(Money.of(new BigDecimal("500000.0"))).isEqualTo(new BigDecimal("500000.00"));
        assertThat(Money.of(new BigDecimal("0.1"))).isEqualTo(new BigDecimal("0.10"));
        assertThat(Money.of(new BigDecimal("12.340"))).isEqualTo(new BigDecimal("12.34"));   // trailing zero only
    }

    @Test
    void nullStaysNull() {
        assertThat(Money.of(null)).isNull();
    }

    @Test
    void neverRoundsSilently() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.005"))).isInstanceOf(ArithmeticException.class);
    }
}
