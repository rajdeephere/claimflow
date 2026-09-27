# Phase 7 — Concurrency control and SQL performance

**Status:** ✅ Complete (JWT security deferred, see below)

## Goal

Make concurrent edits safe (no lost updates) and make the adjusters' main query fast at scale,
with the improvement **measured**, not assumed.

## Delivered

| Item | Location |
|---|---|
| `OptimisticLockingFailureException` → **409** in the shared handler | `common/.../GlobalExceptionHandler` |
| `PreconditionFailedException` → **412** | `common/.../error/` |
| `ETag: "<version>"` on GET / PATCH / assign-adjuster; optional `If-Match` checked before any change | `ClaimController`, `ETags`, `ClaimService.getForUpdate` |
| Flyway V4: `idx_claim_policy_status_created (policy_id, status, created_at DESC)` | `claim-service/.../V4__*.sql` |
| Reproducible benchmark: 500k claims, 3 index variants, `EXPLAIN (ANALYZE, BUFFERS)` | `infra/perf/claims-index-benchmark.sql` |
| Results and analysis | [`docs/sql-performance.md`](../sql-performance.md) |
| Query-plan regression test | `ClaimQueryPlanIntegrationTest` |
| Concurrency tests | `ClaimConcurrencyIntegrationTest` |

## Verification

| Check | Result |
|---|---|
| `mvn install` (all modules) | ✅ **220 tests** (common 5, policy 34, claim 127, validation 33, payment 21) |
| Stale entity flushed after another transaction committed → `ObjectOptimisticLockingFailureException`; the newer change survives | ✅ deterministic (nested `REQUIRES_NEW` transactions) |
| GET ETag → update with it → 200 + new ETag; second update with the old ETag → **412**, nothing written | ✅ |
| 10 concurrent requests, same If-Match → **exactly one 200**; the rest 412 or 409; 1 history row | ✅ |
| 10 concurrent requests, no If-Match → **2 × 200, 8 × 409**; each success has a history row and one version bump | ✅ (numbers vary per run; the invariants are asserted) |
| Index benchmark (fleet policy, page query): **49.9 ms → 3.7 ms → 0.079 ms** | ✅ measured |
| Plan uses the index without a Sort (regression test) | ✅ |
| Live: Flyway V4 applied to `claim_db`; ETag `"0"` → update → `"1"`; stale `"0"` → 412 **through the gateway** | ✅ |

## Issues found & fixed

- **BUG-014: my benchmark seed script never finished.** It looked up each claim's policy with a
  correlated subquery on an unindexed table: 500,000 × 50,000 row scans. Cancelled it with
  `pg_cancel_backend`, added a primary key and a JOIN; the whole benchmark now runs in 7 s. The same
  class of problem as the one being benchmarked.

## Decisions

- [ADR-0011](../adr/0011-optimistic-locking-on-claims.md): optimistic locking → **Accepted**
- [ADR-0026](../adr/0026-conditional-updates-with-etag-if-match.md): ETag / If-Match conditional updates (new)
- [ADR-0027](../adr/0027-composite-index-claims-policy-status-created.md): composite index (new)

## Deferred

- **JWT authentication and role-based authorisation** ([ADR-0013](../adr/0013-jwt-authentication-at-gateway.md),
  still Proposed). Prioritised below CI/CD for the time available; `X-User-Id` remains the stand-in for the actor.
- Count query optimisation (`Slice` instead of `Page`), see `sql-performance.md`.
- `CREATE INDEX CONCURRENTLY` migration pattern for large live tables.
