# ADR-0012: Reliable event publishing with a transactional outbox

- **Status:** Accepted (Proposed in Phase 1; decided and implemented in Phase 4)
- **Date:** 2026-09-27
- **Phase:** 4

## Context

The Claim Service must both commit a DB change (claim → SUBMITTED) and publish `ClaimSubmitted`.
These are two systems with no shared transaction (the **dual-write problem**):

| Order | Failure | Result |
|---|---|---|
| publish, then commit | commit fails | event for a claim that doesn't exist |
| commit, then publish | crash or Kafka down before publish | claim exists, nobody is told; validation never runs |

## Decision

**Transactional Outbox.**

1. `OutboxWriter.append(...)` inserts the full JSON envelope into `outbox_events` **in the same
   transaction** as the business change (`Propagation.MANDATORY` enforces that there is one). It
   captures the correlation ID from the MDC at that moment.
2. `OutboxRelay` (scheduled every 500 ms) selects unpublished rows **in write order** (`seq`) with
   `FOR UPDATE SKIP LOCKED`, sends each with key = claimId and headers, **waits for the broker ack**,
   and sets `published_at`. On a failure it records `attempts` / `last_error` and **stops the batch**,
   so later events never overtake an earlier one.
3. Delivery is at least once (a crash after the ack but before the update re-sends), so consumers
   are idempotent (ADR-0009).
4. A partial index `WHERE published_at IS NULL` keeps the relay query small as history grows.

## Consequences

- ✅ No lost events and no phantom events. Verified: with **Kafka stopped**, FNOL still returned 201;
  the event waited in the outbox and was published automatically after Kafka restarted.
- ✅ Claim intake doesn't depend on Kafka being up.
- ✅ `SKIP LOCKED` lets several claim-service instances relay in parallel without double-publishing.
- ✅ Consumers can produce events atomically with their own processing (outbox + processed_events in one transaction).
- ⚠️ Publishing latency ≈ poll interval (≤ 500 ms). Fine for this workflow.
- ⚠️ The table grows; published rows need periodic cleanup (deferred).
- ⚠️ Stopping the batch on the first failure means one permanently failing event blocks the rest
  (monitor `attempts`; it could be parked after N attempts).

## Alternatives considered

- **`@TransactionalEventListener(AFTER_COMMIT)` then publish:** simpler, but a crash between commit
  and publish loses the event. Rejected: a lost `ClaimSubmitted` means a claim is never validated.
- **Publish inside the transaction:** the event may go out and then the transaction rolls back; rejected.
- **CDC with Debezium** (tail the Postgres WAL into Kafka): no polling and the lowest latency, but
  needs Kafka Connect infrastructure. It's the natural upgrade path; the outbox table stays the same.
- **Kafka transactions + `ChainedTransactionManager`:** best-effort 1PC, not truly atomic across DB and Kafka; deprecated approach.
