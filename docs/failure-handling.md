# Failure Handling

> Living document: every failure mode we have designed for, and how it was verified.

## Synchronous (REST)

| Failure | Behaviour | Verified |
|---|---|---|
| Invalid request (bad JSON, UUID, enum, constraint) | 400 with `violations` | MockMvc tests (BUG-006, BUG-007) |
| Business rule broken | 422 | unit + integration tests |
| Invalid state transition | 409 with allowed targets | exhaustive state tests |
| Downstream service down (via gateway) | 503 `ApiError` | manual, Phase 1 (BUG-004) |
| Downstream slow | 504 after 10 s | configured |
| Client retries FNOL | same `Idempotency-Key` returns the original claim (200) | 8-thread race test |
| Unexpected exception | 500, logged with stack trace, generic message to client | – |

## Asynchronous (Kafka)

| Failure | Behaviour | Verified |
|---|---|---|
| **Kafka down when a claim is filed** | FNOL still returns 201. The event waits in `outbox_events` (attempts and last error recorded) and is published automatically when Kafka is back, with its original correlation ID. | manual: Kafka stopped, FNOL 201, row unpublished (2 attempts), Kafka started, published |
| DB transaction rolls back | its outbox row rolls back too: no event for a change that never happened | by design (same transaction) |
| Relay crashes after send, before marking published | event re-sent on restart; consumers dedupe by `eventId` | dedupe test |
| Same event delivered twice | second copy skipped (`processed_events` `ON CONFLICT DO NOTHING`) | integration + manual (log: "Duplicate … skipping") |
| Transient consumer error (DB blip, optimistic lock) | retried 3× with exponential backoff (500 ms, 1 s, 2 s), then DLT | configured |
| **Malformed message** (not JSON, bad UUID) | straight to `<topic>.DLT`, no retries; partition keeps flowing | integration: valid event after poison still processed |
| **Impossible business event** (e.g. PaymentCompleted for a SUBMITTED claim) | straight to DLT (`InvalidStateTransitionException` is non-retryable); nothing written; not marked processed, so it can be replayed after a fix | integration: DLT headers carry the cause |
| Unknown event type | logged and skipped (tolerant reader) | unit test |
| Consumer instance killed | partitions reassigned after the session timeout (~45 s); uncommitted records redelivered, then deduped | observed manually |
| Payment failed | `PaymentFailed` recorded in claim history; claim stays PAYMENT_INITIATED for retry or manual review | unit test |

## Validation Service (Phase 5)

| Failure | Behaviour | Verified |
|---|---|---|
| **Policy Service down / 5xx / timeout** | `DependencyUnavailableException`: backoff 1 s → 30 s cap for up to 10 min; claim waits in SUBMITTED; then validated | integration (4 × 503 then OK) + **live** (service stopped and restarted) |
| Policy Service 404 "Policy not found" | business answer: `ClaimValidationFailed` | integration + live |
| 404 without "Policy not found" (misrouted URL) | treated as unavailable, **never** as a rejection | `PolicyClientTest` |
| Our request rejected (other 4xx) | our bug: DLT immediately | `PolicyClientTest` |
| Same `ClaimSubmitted` delivered twice | same output eventId, deduped downstream | integration |
| Result arrives after the claim moved on (replay, re-validation) | claim-service logs "Stale … ignoring" and acknowledges; no DLT | unit test (the live replay found BUG-011; the fix was verified by the unit test, not re-run live) |

## Payment Service (Phase 6)

| Failure | Behaviour | Verified |
|---|---|---|
| Same `ClaimApproved` twice | skipped by `processed_events` | integration + **live replay** |
| Different event for an already-paid claim | "already has a payment; ignoring" | integration + **live replay** |
| Both application checks bypassed | `UNIQUE (claim_id)` rejects the second payment | integration (direct insert) |
| Crash after the bank paid, before recording | retry uses the same idempotency key, and the gateway returns the original result | unit (key = paymentId) + design (ADR-0023) |
| Bank declines | payment FAILED, `PaymentFailed`; claim-service records it; claim stays PAYMENT_INITIATED for manual review | integration |
| Gateway unavailable | stays INITIATED, retried each tick with the same key; FAILED after 5 attempts | unit |
| Two processor instances on one payment | gateway idempotency + `@Version`; the loser logs and moves on | design |
| `ClaimApproved` without coverage terms (claim validated before Phase 6) | DLT (a human must decide) | integration |
| Approved amount not above the deductible | refused at approval in Claim Service (422), so it never reaches Payment | unit + live |

## Concurrency (Phase 7)

| Failure | Behaviour | Verified |
|---|---|---|
| Two transactions update the same claim at once | `@Version`: the second commit fails; **409** over REST, retried for Kafka consumers | 10-thread race: 2 × 200, 8 × 409, no lost update |
| User submits based on a stale screen | `If-Match` mismatch: **412**, nothing written | integration + live through the gateway |

## Dead Letter Topics

- Name: `<original-topic>.DLT`, same partition as the original.
- Headers added by Spring Kafka: `kafka_dlt-original-topic`, `-original-partition`, `-original-offset`,
  `-exception-fqcn` (always the `ListenerExecutionFailedException` wrapper), **`-exception-cause-fqcn`**
  and `-exception-message` (the real cause).
- The service logs `Sent <topic>-<p>@<offset> (key …) to <topic>.DLT after failure: <RootCause>: <message>`.
- Replay is manual for now (fix the cause, then re-publish from the DLT). A replay tool is future work.

## Not yet handled (future phases)

- DLT alerting and a replay endpoint.
