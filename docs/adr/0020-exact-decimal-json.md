# ADR-0020: Exact-decimal JSON settings for every service

- **Status:** Accepted (extends ADR-0010)
- **Date:** 2026-09-27
- **Phase:** 4

## Context

ADR-0010 makes money `BigDecimal` / `NUMERIC(15,2)`. BUG-009 showed JSON can still change it. Event
payloads pass through a Jackson `JsonNode` tree, and Jackson's defaults:

| Step | Default behaviour | Example |
|---|---|---|
| object → tree | `BigDecimal` normalised (trailing zeros stripped) | `200000.00` → `2E+5` |
| tree → text | written as `BigDecimal.toString()` | `"claimedAmount":2E+5` |
| text → tree | decimals parsed as `double` | `200000.00` → `200000.0`; above ~17 significant digits the value itself is rounded (measured) |

## Decision

A `Jackson2ObjectMapperBuilderCustomizer` in `common` (`JsonConfig`) applies to every service's ObjectMapper:

- `JsonNodeFactory.withExactBigDecimals(true)`: keep the scale in trees
- `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`: parse decimals as `BigDecimal`, never `double`
- `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN`: `200000.00`, never `2E+5`

`JsonConfig.configure(mapper)` applies the same settings to hand-built mappers (tests).
Tests compare money with `equals` (value **and** scale), because `compareTo` hides this class of bug.

## Consequences

- ✅ Amounts are byte-for-byte stable across services: `"claimedAmount":200000.00` on the wire (verified).
- ✅ Safe for consumers in other languages and for amounts beyond double precision.
- ⚠️ Every service must use the Spring-managed ObjectMapper (or `JsonConfig.configure`); a `new ObjectMapper()` reintroduces the problem.

## Alternatives considered

- **Amounts as JSON strings** (`"200000.00"`): unambiguous everywhere, common in payment APIs;
  slightly less natural for consumers. A valid alternative if non-Java consumers appear.
- **Minor units as integers** (`20000000` paise): exact and compact; less readable and needs a currency's scale.
