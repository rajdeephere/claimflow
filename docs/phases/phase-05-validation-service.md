# Phase 5 — Validation Service

**Status:** ✅ Complete

## Goal

Replace simulated validation events with a real, stateless rules engine: consume `ClaimSubmitted`,
check the policy through Policy Service, apply business rules, publish `ClaimValidated` or
`ClaimValidationFailed`, all without a database.

## Delivered

| Item | Location |
|---|---|
| Kafka consumer of `claim.events` (group `validation-service`), ignores non-`ClaimSubmitted` events | `messaging/ClaimSubmittedListener` |
| Policy Service client: `RestClient` + JDK `HttpClient`, 2 s connect / 3 s read timeouts, correlation header, status classification | `policy/PolicyClient` |
| Rules engine (Strategy pattern): `ValidationRule` beans injected as an ordered `List`, all rules evaluated | `ClaimValidator`, `rules/*` |
| Publisher with **deterministic output event IDs** (stateless idempotency) | `messaging/ValidationResultPublisher` |
| `common`: `DependencyUnavailableException` with a long, separate Kafka backoff; `ClaimValidated.warnings` (optional field) | `common/` |
| `claim-service`: stale validation results ignored instead of dead-lettered; warnings recorded in history | `InboundEventHandler` |

## Rules

| Order | Rule | Outcome |
|---|---|---|
| – | Policy exists (Policy Service 404 "Policy not found") | FAIL |
| 10 | `PolicyInForceRule`: policy active and incident date within the period | FAIL |
| 20 | `CoverageOnPolicyRule`: the loss type is covered | FAIL |
| 30 | `AmountAboveDeductibleRule`: claimed amount > deductible | FAIL |
| 40 | `CoverageLimitRule`: claimed − deductible > limit | **WARN** (payout will be capped) |
| 50 | `LateNotificationRule`: reported more than 30 days after the incident | **WARN** |

Any FAIL publishes `ClaimValidationFailed` with **all** reasons; otherwise `ClaimValidated` with the
limit, deductible and any warnings.

## Policy Service responses

| Response | Treated as | Kafka handling |
|---|---|---|
| 200 | coverage answer | – |
| 404 with `Policy not found` | business answer: reject | – |
| 404 **without** it (e.g. wrong base URL) | `DependencyUnavailableException` | long retry, **never** a rejection |
| 5xx, timeout, connection refused | `DependencyUnavailableException` | backoff 1 s → 2 s → … (max 30 s) for up to 10 min, then DLT |
| other 4xx | `IllegalArgumentException` (our bug) | DLT immediately |

## Verification

| Check | Result |
|---|---|
| `mvn install` (all modules) | ✅ 192 tests (common 5, policy 34, claim 120, validation 33) |
| Rules: boundary tests at the deductible (19999.99 / 20000.00 / 20000.01) and limit + deductible (520000.00 / 520000.01), 30 vs 31 days late | ✅ |
| `PolicyClient` over real HTTP (stub server): 200, "not found" 404, unknown 404, 503, read timeout, connection refused, 400; correlation header forwarded | ✅ |
| Kafka: covered → `ClaimValidated`; cancelled → failed; unknown policy → failed; malformed → DLT; other claim events ignored | ✅ |
| Same `ClaimSubmitted` twice → two results with the **same eventId** | ✅ |
| Policy Service returning 503 four times, then healthy → validated, not dead-lettered | ✅ |
| **Live, 4 services + Kafka + Postgres, no simulated events:** COLLISION 200 000 → UNDER_REVIEW; THEFT → REJECTED (no coverage); unknown policy → REJECTED; 900 000 → UNDER_REVIEW with cap warning; 15 000 → REJECTED (below deductible), each within ~1 s | ✅ |
| Live: one correlation ID (`e2e-covered`) in gateway, claim and validation logs | ✅ |
| **Live outage:** Policy Service stopped → FNOL 201, claim stays SUBMITTED, validation retries with growing gaps; Policy Service restarted → claim UNDER_REVIEW automatically | ✅ |

## Issues found & fixed

- **BUG-011: harmless stale validation results were dead-lettered.** When Validation Service first
  joined, it replayed history (`auto-offset-reset: earliest`) and re-validated a claim that was already
  UNDER_REVIEW; claim-service treated the result as an invalid transition and sent it to the DLT.
  A validation result for a claim no longer SUBMITTED is now acknowledged and ignored (with a warning
  log). Payment events in the wrong state still go to the DLT, because those are real anomalies.
- **BUG-012: flaky `PolicyClientTest`.** The stub HTTP server had no executor, so it handled requests
  on one thread; the deliberately slow response in one test made the next test time out, depending on
  execution order. Fixed with a thread pool.
- **Log clarity:** a refused connection logged `ClosedChannelException: null`. The retry log now
  falls back to the deepest cause that has a message (`DependencyUnavailableException: Policy Service
  unreachable … (root: ClosedChannelException)`). Unit-tested.

## Decisions

- [ADR-0021](../adr/0021-deterministic-event-ids-for-stateless-idempotency.md): deterministic output event IDs (new)
- [ADR-0022](../adr/0022-classifying-consumer-failures.md): classifying consumer failures: retry briefly, retry long, DLT or ignore (new)
- [ADR-0004](../adr/0004-synchronous-rest-for-policy-lookup.md): REST policy lookup, now implemented as designed

## Deferred

- Circuit breaker around the Policy Service call. With blocking, per-partition retries the consumer
  already backs off, so a breaker adds little until there are concurrent callers.
- Caching coverage answers; externalising rule thresholds per product.
