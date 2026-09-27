# ADR-0009: Idempotent Kafka consumers via a processed_events table

- **Status:** Accepted (Proposed in Phase 1; implemented in Phase 4)
- **Date:** 2026-09-27
- **Phase:** 4

## Context

Kafka delivers **at least once**: after a crash, a rebalance, or a producer/outbox retry, the same event
can arrive again. For payments, processing `ClaimApproved` twice would pay a claim twice. For
claims, `PaymentCompleted` twice would write duplicate history.

## Decision

- Every event carries a unique `eventId` (in the envelope and as a header).
- Each consuming service has `processed_events(event_id, consumer_name, event_type, processed_at)`
  with primary key `(event_id, consumer_name)`.
- The consumer inserts that row **in the same DB transaction** as the business change, using
  `INSERT … ON CONFLICT DO NOTHING`. **0 rows inserted** means a duplicate: skip it and acknowledge.
  - Not "catch `DuplicateKeyException`": a constraint violation marks the Spring transaction
    rollback-only (the trap from ADR-0017). `ON CONFLICT` just reports a row count.
  - Concurrent duplicates are safe: the second insert blocks on the first transaction's row lock,
    then sees the conflict.
- The Kafka offset is committed **after** the transaction commits (`ack-mode: record`).
- If processing fails, the whole transaction rolls back, **including the processed_events row**,
  so the event can be retried or replayed from the DLT.
- As defence in depth, Payment will also have `UNIQUE (claim_id)` on payments (Phase 6).

We describe this as **effectively-once business processing**, not "Kafka exactly-once".

## Consequences

- ✅ Duplicates are harmless, verified with the same event sent twice (integration and manual).
- ✅ Consume + update + produce is atomic without Kafka transactions, because produced events go
  to the outbox in the same DB transaction (ADR-0012).
- ✅ Broker-agnostic: the same pattern works for RabbitMQ or IBM MQ.
- ⚠️ One extra insert per event; rows can be purged after the topic retention period.

## Alternatives considered

- **Kafka transactions / EOS (`read_process_write`):** exactly-once only for Kafka-to-Kafka; it
  doesn't cover the PostgreSQL write. Not sufficient on its own.
- **Natural idempotency only** (e.g. "is the claim already SETTLED?"): works for some events, not
  all (history rows, notes); kept as a secondary guard via the state machine.
- **Dedupe cache in memory / Redis:** not atomic with the DB change; a crash between the two breaks it.
