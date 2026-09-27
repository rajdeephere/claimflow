# ADR-0012: Reliable event publishing (dual-write problem)

- **Status:** Proposed
- **Date:** 2026-09-27
- **Phase:** 4

## Context

The Claim Service must both commit a DB change (claim → SUBMITTED) and publish `ClaimSubmitted`.
These are two systems: if the DB commits but the publish fails (or the reverse), services disagree.

## Decision

*To be decided in Phase 4.* Leading option: **Transactional Outbox**. Write the event to an
`outbox` table in the same DB transaction as the business change; a scheduled relay publishes
unsent rows to Kafka and marks them sent. Combined with idempotent consumers (ADR-0009),
duplicates from relay retries are harmless.

Simpler fallback: publish after commit (`@TransactionalEventListener(phase = AFTER_COMMIT)`),
accepting a small window where an event can be lost on a crash, documented as a known limitation.

## Consequences

- ✅ Outbox: no lost events; the event order matches commit order.
- ⚠️ Outbox: extra table and relay; at-least-once publishing.

## Alternatives considered

- **Publish inside the transaction:** the event can be sent even if the transaction then rolls back; rejected.
- **CDC with Debezium:** robust, but heavy infrastructure for this scope.
