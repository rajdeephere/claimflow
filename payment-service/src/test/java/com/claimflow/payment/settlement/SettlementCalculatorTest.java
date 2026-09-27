package com.claimflow.payment.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    @ParameterizedTest(name = "approved {0}, deductible {1}, limit {2} -> {3} (capped={4})")
    @CsvSource({
            // the worked example from the design doc: 2,00,000 - 20,000 = 1,80,000
            "200000.00, 20000.00, 500000.00, 180000.00, false",
            // capped at the coverage limit
            "900000.00, 20000.00, 500000.00, 500000.00, true",
            // exactly at the limit: not capped
            "520000.00, 20000.00, 500000.00, 500000.00, false",
            // one paisa over the deductible
            "20000.01,  20000.00, 500000.00, 0.01,      false",
            // zero deductible
            "1000.00,   0.00,     500000.00, 1000.00,   false",
            // inputs without a scale still produce scale-2 money
            "200000,    20000,    500000,    180000.00, false"
    })
    void calculatesPayable(String approved, String deductible, String limit, String expected, boolean capped) {
        SettlementCalculator.Result r = calculator.calculate(new BigDecimal(approved), new BigDecimal(deductible),
                new BigDecimal(limit));

        assertThat(r.payable()).isEqualTo(new BigDecimal(expected));   // equals: value AND scale 2
        assertThat(r.cappedAtLimit()).isEqualTo(capped);
    }

    @Test
    void roundsHalfEvenToTwoDecimals() {
        // 100.125 - 0 -> 100.12 (HALF_EVEN rounds a trailing 5 to the even neighbour), 100.135 -> 100.14
        assertThat(calculator.calculate(new BigDecimal("100.125"), BigDecimal.ZERO, new BigDecimal("1000")).payable())
                .isEqualTo(new BigDecimal("100.12"));
        assertThat(calculator.calculate(new BigDecimal("100.135"), BigDecimal.ZERO, new BigDecimal("1000")).payable())
                .isEqualTo(new BigDecimal("100.14"));
    }

    @Test
    void nothingPayableIsRejected() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("500000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing payable");
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> calculator.calculate(null, BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.TEN, new BigDecimal("-1"), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
