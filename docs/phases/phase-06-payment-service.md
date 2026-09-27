# Phase 6 — Payment Service

**Status:** ✅ Complete: the claim lifecycle now runs end to end with real services

## Goal

Settle approved claims correctly (BigDecimal), move the money through a (simulated) payment gateway,
and make paying a claim twice impossible, even under redelivery, replay, crashes and concurrent instances.

## Delivered

| Item | Location |
|---|---|
| Flyway V1 (`payment_db`): `settlements`, `payments` (`UNIQUE (claim_id)`), `outbox_events`, `processed_events` | `payment-service/.../db/migration/V1__create_payment_tables.sql` |
| `SettlementCalculator`: `min(approved − deductible, limit)`, scale 2, HALF_EVEN | `settlement/` |
| `ClaimApprovedHandler`: settlement + INITIATED payment + `PaymentInitiated` in one transaction, three duplicate guards | `messaging/` |
| `PaymentProcessor`: calls the gateway **outside** any DB transaction, idempotency key = paymentId; COMPLETED / FAILED + event via outbox; max attempts then FAILED | `payment/PaymentProcessor` |
| `PaymentGateway` contract + `SimulatedPaymentGateway` (idempotent, declines above a bank limit, latency) | `gateway/` |
| Read API: `GET /api/v1/payments/{id}`, `GET /api/v1/payments?claimId=`, with the settlement breakdown | `payment/PaymentController` |
| Outbox + processed-events store (same design as claim-service, copied, see ADR-0024) | `outbox/`, `messaging/ProcessedEventStore` |
| **claim-service:** Flyway V3 stores `coverage_limit` / `deductible` from `ClaimValidated`; `ClaimApproved` carries them; approval must exceed the deductible | `Claim.markValidated`, `Claim.approve` |

## Settlement

| Approved | Deductible | Limit | Payable | Capped |
|---|---|---|---|---|
| 2,00,000.00 | 20,000.00 | 5,00,000.00 | **1,80,000.00** | no (the spec's example) |
| 9,00,000.00 | 20,000.00 | 5,00,000.00 | **5,00,000.00** | yes |
| 20,000.01 | 20,000.00 | 5,00,000.00 | 0.01 | no |
| 20,000.00 | 20,000.00 | 5,00,000.00 | refused by Claim Service at approval (422) | – |

## No double payment: defence in depth

| Guard | Catches | Where |
|---|---|---|
| 1. `processed_events` | the **same** event again (redelivery, outbox re-send) | `ClaimApprovedHandler` |
| 2. "claim already has a payment?" | a **different** event for the same claim (replay, re-approval) | `ClaimApprovedHandler` |
| 3. `UNIQUE (claim_id)` | anything that slips past 1 and 2 (bug, race) | `payments` table |
| 4. Gateway idempotency key = paymentId | crash after the bank paid but before we recorded it, or two processor instances | `PaymentProcessor` ↔ gateway |
| 5. `@Version` on payment | two instances recording the outcome concurrently | `Payment` |

## Verification

| Check | Result |
|---|---|
| `mvn install` (all modules) | ✅ **215 tests** (common 5, policy 34, claim 122, validation 33, payment 21) |
| Settlement table incl. rounding (`100.125 → 100.12`, `100.135 → 100.14`), scale always 2, `equals` assertions | ✅ |
| Processor: success / decline / gateway down twice then OK path / max attempts → FAILED / completed payment never re-sent | ✅ unit |
| Kafka: `ClaimApproved` → `PaymentInitiated` then `PaymentCompleted` (same partition, in order, correlation ID kept) | ✅ integration |
| Same event twice + a new event for the same claim → **1 payment, 1 bank transfer** | ✅ integration |
| Second payment row for a claim → `DataIntegrityViolationException` | ✅ integration |
| Above bank limit → `PaymentFailed`; missing coverage terms → DLT | ✅ integration |
| **Live, all 5 services:** FNOL → auto-validated → adjuster approves 2,00,000 → **SETTLED with 1,80,000.00** → closed; 8 history rows | ✅ |
| **Live replay** of the real `ClaimApproved` (same ID, then new ID) → "Duplicate … skipping", "already has a payment; ignoring"; still 1 payment, 1 transfer | ✅ |
| Live: approve = deductible → 422; approve 9,00,000 → paid 5,00,000.00, `cappedAtLimit: true` | ✅ |
| Raw JSON amounts exact (`"amount":180000.00`) | ✅ |

## Issues found & fixed

- **BUG-013: Java 21 syntax in a Java 17 project.** I wrote a pattern-matching `switch` over the
  sealed gateway result (a Java 21 feature, and my JDK is 21), but `maven.compiler.release=17`
  rejected it (`patterns in switch statements are not supported in -source 17`). Rewritten with
  `instanceof` patterns. The build caught it before it could reach a Java 17 runtime.

## Decisions

- [ADR-0023](../adr/0023-payment-idempotency-and-gateway-call-outside-transaction.md): payment idempotency and the gateway call outside the transaction (new)
- [ADR-0024](../adr/0024-duplicate-outbox-code-rule-of-three.md): copy the outbox code instead of sharing it (new)
- [ADR-0025](../adr/0025-event-carried-coverage-terms.md): coverage terms travel with the events (new)

## Deferred

- Manual retry endpoint for FAILED payments (needs a new idempotency key per attempt, and an audit of who retried).
- Real gateway integration (webhooks for asynchronous confirmation, reconciliation reports).
- Partial payments / multiple payments per claim (exposures).
