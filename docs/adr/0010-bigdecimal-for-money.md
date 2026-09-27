# ADR-0010: BigDecimal and NUMERIC for monetary values

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

`double` is binary floating point: `0.1 + 0.2 != 0.3`. Rounding errors in settlements are unacceptable.

## Decision

All amounts are `java.math.BigDecimal` in Java and `NUMERIC(15,2)` in PostgreSQL. Arithmetic
uses explicit `RoundingMode.HALF_EVEN` (banker's rounding) with scale 2. Comparisons use
`compareTo`, never `equals` (since `2.0` and `2.00` are not `equals`). Values are constructed from
strings (`new BigDecimal("0.1")`), never from doubles.

## Consequences

- ✅ Exact decimal arithmetic.
- ⚠️ More verbose code; slightly slower (irrelevant at this scale).

## Alternatives considered

- **`long` minor units (paise/cents):** also exact and fast; less readable for multi-currency; not chosen.
