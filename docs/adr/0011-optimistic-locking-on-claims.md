# ADR-0011: Optimistic locking on claims

- **Status:** Accepted (Proposed in Phase 1; implemented in Phase 7, together with ADR-0026)
- **Date:** 2026-09-27
- **Phase:** 7

## Context

An adjuster, a claims manager and Kafka consumers can all update the same claim. Last-write-wins
would silently lose updates, e.g. two concurrent adjuster assignments where one simply disappears.

## Decision

- `Claim` (and `Payment`, `Policy`) carry a JPA `@Version` column. Every UPDATE Hibernate issues is
  `... WHERE id = ? AND version = ?` and increments the version; 0 rows updated throws
  `ObjectOptimisticLockingFailureException`.
- REST: the shared `GlobalExceptionHandler` maps `OptimisticLockingFailureException` to **409 Conflict**
  ("modified concurrently, reload and retry").
- Kafka consumers: the exception is **retryable** (not in the non-retryable list), so the record is
  redelivered, reprocessed on the fresh state, and deduped if already applied.
- This protects **concurrent transactions**. Stale *screens* across HTTP requests are handled by
  ETag / If-Match (ADR-0026).

## Consequences

- ✅ No lost updates. Verified: 10 simultaneous assignments → 2 succeeded, **8 rejected with 409**;
  every success has its history row and exactly one version bump.
- ✅ No DB locks held between read and write; high concurrency.
- ⚠️ Conflicts become errors callers must handle (reload and retry).

## Alternatives considered

- **Pessimistic locking (`SELECT ... FOR UPDATE`):** blocks other writers for the transaction's
  duration; better for very hot rows (counters); unnecessary here.
- **No locking:** silent lost updates; rejected.
