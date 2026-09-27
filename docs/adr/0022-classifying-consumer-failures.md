# ADR-0022: Classifying consumer failures: retry briefly, retry long, dead-letter, or ignore

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 5

## Context

Phase 4 had two outcomes for a failed record: retry 3× quickly then DLT, or DLT immediately for
non-retryable errors. Phase 5 exposed two cases that fit neither:

1. **A dependency outage** (Policy Service down): the message is fine, so dead-lettering after
   ~3.5 s would push perfectly good claims into manual handling during a short outage.
2. **A stale duplicate** (a validation result for a claim that already moved on): not an error at all,
   but it failed the state machine and ended up in the DLT (BUG-011), burying real problems in noise.

## Decision

Every failure a consumer can meet is put in one of four classes:

| Class | Examples | Handling |
|---|---|---|
| **Transient** | DB blip, optimistic lock, broker hiccup | retry 3× (500 ms, 1 s, 2 s), then DLT |
| **Dependency unavailable** (`DependencyUnavailableException`) | Policy Service 5xx, timeout, refused, unexpected 404 | exponential backoff 1 s → 30 s cap, for up to **10 minutes**, then DLT |
| **Permanent** (non-retryable) | malformed JSON, invalid transition for payment events, our own 4xx bug | DLT **immediately** |
| **Expected no-op** | stale or late validation result, unknown event type, duplicate eventId | log and **acknowledge**, no DLT |

Implemented in the shared `DefaultErrorHandler` (`setBackOffFunction` for the dependency class,
`addNotRetryableExceptions` for the permanent class) and in the handlers (no-ops return normally).

**Principle: the DLT is a queue for humans.** Only messages a person must look at belong there.

## Consequences

- ✅ A Policy Service outage only *delays* validation (verified live: stopped, FNOL, restarted, validated).
- ✅ The DLT stays meaningful.
- ⚠️ During a long dependency outage the partition is blocked (blocking retries). That's intended:
  every message on it needs the same dependency, and ordering per claim is preserved.
- ⚠️ No-op classification must be narrow: over-using it would hide real bugs. Only validation
  results for non-SUBMITTED claims are treated as stale; payment anomalies still dead-letter.

## Alternatives considered

- **Non-blocking retry topics (`@RetryableTopic`):** frees the partition during long backoffs, but
  loses per-claim ordering. Not acceptable for a state machine.
- **Circuit breaker (Resilience4j):** fails fast when the dependency is known to be down; valuable
  with many concurrent callers. With sequential, backed-off consumers it adds little; noted as future work.
- **Pause the listener container** while the dependency is down: another valid approach (explicit
  `pause()`/`resume()`), with more moving parts than a backoff function.
