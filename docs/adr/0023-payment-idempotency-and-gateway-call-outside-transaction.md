# ADR-0023: Payment idempotency, and calling the gateway outside the transaction

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 6

## Context

Paying a claim twice is the worst failure the platform can have. Kafka is at-least-once; the outbox
can re-send; events can be replayed; services crash at arbitrary points; several instances may run.
Separately, the bank call is slow and external: doing it inside a DB transaction would hold a
connection and row locks for its whole duration, and still couldn't be rolled back.

## Decision

**Two steps.**

1. **Consume `ClaimApproved` (one DB transaction):** mark the event processed, check the claim has no
   payment, calculate and store the settlement, insert the payment as `INITIATED`, append
   `PaymentInitiated` to the outbox.
2. **`PaymentProcessor` (scheduled):** for each INITIATED payment, call the gateway **with
   idempotency key = paymentId, outside any transaction**, then in a short transaction record
   COMPLETED or FAILED and append `PaymentCompleted` / `PaymentFailed` to the outbox.

**Guards (defence in depth):** `processed_events` (same event) → "claim already has a payment"
(different event, same claim) → `UNIQUE (claim_id)` (the database's final word) → gateway
idempotency key (crash between the bank paying and us recording it) → `@Version` (two instances
recording the same outcome).

**Gateway failures:**
- *Declined* (business): FAILED immediately, `PaymentFailed`, manual review.
- *Unavailable / timeout* (outcome unknown): stay INITIATED; retry on the next tick **with the same
  key**; after `max-attempts` (5): FAILED for manual review.

## Consequences

- ✅ A claim is paid at most once, verified with redelivery, replay (live) and a direct DB insert.
- ✅ No DB connection or lock is held during the bank call.
- ✅ Crash-safe at every point: before the call (retried), after it (same key returns the original result).
- ⚠️ Depends on the provider supporting idempotency keys (Stripe, Adyen and most bank APIs do); with
  one that doesn't, you need reconciliation (query the provider by reference before retrying).
- ⚠️ Payments complete asynchronously (poll interval ≤ 1 s here).

## Alternatives considered

- **Call the gateway inside the consumer transaction:** a crash after paying but before commit
  redelivers the event and pays again (no idempotency key) or holds locks during a slow call. Rejected.
- **Distributed transaction (XA / 2PC) across DB and bank:** banks don't take part in XA; heavy and fragile.
- **Saga with compensation (refund if something fails later):** a refund is a real-world money
  movement with fees and customer impact. Preventing the double payment is far better than compensating for it.
