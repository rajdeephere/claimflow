# ADR-0009: Idempotent Kafka consumers via a processed_events table

- **Status:** Proposed
- **Date:** 2026-09-27
- **Phase:** 4

## Context

Kafka delivers **at least once**: after a crash or rebalance an event can be redelivered.
For payments, processing `ClaimApproved` twice would pay a claim twice.

## Decision

Every event carries a unique `eventId`. Each consuming service has
`processed_events(event_id PK, consumer_name, processed_at)`. In **one DB transaction** the
consumer inserts the `processed_events` row and applies the business change; a primary-key
violation means it's a duplicate, so it skips the event and acknowledges it. The offset is
committed only after the transaction commits. As defence in depth, `payments.claim_id` is `UNIQUE`.

We describe this as **effectively-once business processing**, not "Kafka exactly-once".

## Consequences

- ✅ Duplicate events are harmless, which makes redelivery and retries safe.
- ✅ Works with any broker, not only Kafka.
- ⚠️ An extra table and insert per event; old rows can be purged after the retention window.

## Alternatives considered

- **Kafka transactions / EOS:** exactly-once only covers Kafka-to-Kafka (consume, produce,
  commit offsets); it doesn't cover the PostgreSQL write. Not sufficient on its own.
- **Natural idempotency only** (unique constraint on claim_id): catches payments, but not every
  event type has a natural key; used as a second guard.
