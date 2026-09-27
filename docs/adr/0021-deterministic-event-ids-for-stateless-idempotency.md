# ADR-0021: Deterministic event IDs for stateless idempotency

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 5

## Context

Validation Service consumes `ClaimSubmitted` and produces a validation result. It has **no
database**, so it can't keep a `processed_events` table (ADR-0009). Kafka may deliver the same
`ClaimSubmitted` more than once (redelivery after a crash, outbox re-send, offset reset or replay).
With random output IDs, each delivery would produce a *new* result event, and claim-service's dedupe
by `eventId` couldn't recognise the duplicates.

## Decision

The output event's ID is **derived from the input event's ID**:

```java
UUID.nameUUIDFromBytes(("validation-service:" + claimSubmitted.eventId()).getBytes(UTF_8))   // type-3 UUID
```

- Same input event → same output `eventId`, so the downstream `processed_events` dedupes it.
- The service prefix keeps IDs from colliding if another service derives IDs from the same input.
- The result is published directly (no outbox is needed without a database); if the send isn't acked,
  the listener throws, the offset isn't committed, and the whole step is simply repeated.

## Consequences

- ✅ Idempotent end to end without any state in Validation Service (verified: the same input twice gives the same output ID).
- ✅ Validation Service can scale horizontally and be restarted or replayed freely.
- ⚠️ Idempotency is by *ID*, not by *content*: if the policy changed between two deliveries, the
  second result could differ but is still dropped as a duplicate. That's acceptable: the first result was correct when it was made.
- ⚠️ Relies on the upstream `eventId` being stable across re-sends (true for the outbox, ADR-0012).

## Alternatives considered

- **Give Validation Service a database** just for dedupe: works, but adds state and infrastructure to a pure function.
- **Kafka transactions (consume-process-produce EOS):** exactly-once within Kafka for this hop; more
  configuration (transactional IDs, `read_committed` everywhere), and it doesn't help with replays.
- **Rely on the state machine** (the second result is an invalid transition): works, but turns every
  duplicate into an error (see BUG-011 / ADR-0022).
