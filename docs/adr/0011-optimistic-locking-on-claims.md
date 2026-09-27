# ADR-0011: Optimistic locking on claims

- **Status:** Proposed
- **Date:** 2026-09-27
- **Phase:** 7

## Context

An adjuster and a Kafka consumer (or two adjusters) may update the same claim at the same time.
Last-write-wins would silently lose an update.

## Decision

Add a `version BIGINT` column with JPA `@Version` on `Claim`. Hibernate adds
`WHERE version = ?` to updates; a stale update throws `ObjectOptimisticLockingFailureException`,
which REST maps to **409 Conflict** ("reload and retry"). Kafka consumers retry automatically.

## Consequences

- ✅ No DB locks held during user think-time; high concurrency.
- ⚠️ Conflicts surface as errors the caller must handle.

## Alternatives considered

- **Pessimistic locking (`SELECT ... FOR UPDATE`):** blocks other writers; suited to very
  high-contention rows; unnecessary here.
