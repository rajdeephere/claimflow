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

## Dead Letter Topics

- Name: `<original-topic>.DLT`, same partition as the original.
- Headers added by Spring Kafka: `kafka_dlt-original-topic`, `-original-partition`, `-original-offset`,
  `-exception-fqcn` (always the `ListenerExecutionFailedException` wrapper), **`-exception-cause-fqcn`**
  and `-exception-message` (the real cause).
- The service logs `Sent <topic>-<p>@<offset> (key …) to <topic>.DLT after failure: <RootCause>: <message>`.
- Replay is manual for now (fix the cause, then re-publish from the DLT). A replay tool is future work.

## Not yet handled (future phases)

- Concurrent REST update on the same claim: optimistic lock → 409 mapping (Phase 7).
- Circuit breaker around synchronous calls from Validation to Policy (Phase 5).
- DLT alerting and a replay endpoint.
