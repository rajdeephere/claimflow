# Phase 3 — Claim Service

**Status:** ✅ Complete

## Goal

The ClaimCenter-like core: First Notice of Loss (FNOL), a controlled claim lifecycle, adjuster
assignment and a complete audit trail.

## Delivered

| Item | Location |
|---|---|
| Flyway `V1__create_claim_tables.sql`: `adjusters`, `claims`, `claim_history`, `claim_number_seq` | `claim-service/src/main/resources/db/migration/` |
| Lifecycle state machine: one transition table with a trigger source (USER / SYSTEM) per transition | `ClaimStatus`, `TransitionSource` |
| `Claim` aggregate: `approve`, `reject`, `close`, `assignAdjuster`, `transitionTo`; no `setStatus` | `Claim.java` |
| Append-only audit trail written in the same transaction as each change | `ClaimHistory` (`@Immutable`), `ClaimService.record` |
| Idempotent FNOL via `Idempotency-Key` header, safe under concurrent retries | `ClaimService.fileFnol` |
| Paged "claims of a policy by status, newest first" | `GET /api/v1/claims?policyId=&status=` |
| `applySystemTransition(...)`, the entry point Kafka consumers will call in Phase 4 | `ClaimService` |
| `common`: `ClockConfig` moved here; `PageResponse`; handler for `HandlerMethodValidationException` | `common/` |
| 103 tests | `claim-service/src/test/` |

## Lifecycle

| From | To | Triggered by | Extra rule |
|---|---|---|---|
| *(FNOL)* | SUBMITTED | user | – |
| SUBMITTED | UNDER_REVIEW | **system** (validation passed) | – |
| SUBMITTED | REJECTED | **system** (validation failed) | reason required |
| UNDER_REVIEW | APPROVED | user (adjuster) | adjuster assigned; 0 < approved ≤ claimed |
| UNDER_REVIEW | REJECTED | user (adjuster) | reason required |
| APPROVED | SETTLEMENT_PENDING | **system** | – |
| SETTLEMENT_PENDING | PAYMENT_INITIATED | **system** | – |
| PAYMENT_INITIATED | SETTLED | **system** (payment completed) | – |
| SETTLED | CLOSED | user | – |
| REJECTED | CLOSED | user | – |

- A transition not in the table (e.g. `CLOSED → APPROVED`) returns **409**, with the allowed targets in the message.
- A user requesting a system-only transition returns **422**.
- Adjusters can be assigned or reassigned only in `SUBMITTED` / `UNDER_REVIEW` (otherwise **409**).
- Responses include `allowedNextStatuses`, so a UI can show only valid actions.

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/api/v1/claims` (+ optional `Idempotency-Key`) | 201 new / **200 replay** | 400 |
| GET | `/api/v1/claims/{claimId}` | 200 | 400, 404 |
| GET | `/api/v1/claims?policyId=&status=&page=&size=` | 200 `PageResponse` | 400 (size > 100, missing policyId) |
| PATCH | `/api/v1/claims/{claimId}/status` `{targetStatus, approvedAmount?, reason?}` | 200 | 404, 409 invalid transition, 422 rule |
| POST | `/api/v1/claims/{claimId}/assign-adjuster` `{adjusterId}` | 200 | 404, 409 wrong state, 422 unknown/inactive adjuster |
| GET | `/api/v1/claims/{claimId}/history` | 200, oldest first | 404 |
| POST / GET | `/api/v1/adjusters` | 201 / 200 | 400, 409 duplicate email |

`X-User-Id` identifies who acted (recorded in history) until JWT arrives in Phase 7.

## Verification

| Check | Result |
|---|---|
| `mvn install` (all modules) | ✅ 137 tests (policy 34, claim 103), 0 failures |
| All 64 (from, to) status pairs vs. a hand-written expectation; graph checks: all reachable, no dead ends, CLOSED only terminal | ✅ 69 tests |
| Integration (Testcontainers): full happy path with 8 history rows in order; `CLOSED → APPROVED` = 409 and writes nothing | ✅ |
| **8 concurrent FNOLs with one Idempotency-Key**: exactly one 201, all return the same claim, 1 row in DB | ✅ |
| List by policy + status, newest first, paged | ✅ |
| Manual through the gateway: FNOL, 200 on retry, 422 system-only, 409 invalid, 400 with field violations, history with actor + correlation ID `demo-p3` | ✅ |

## Issues found & fixed

- **BUG-007: field violations silently missing from 400s.** Once a controller method has parameter
  constraints (`@Size` header, `@Max` page size), Spring 6.1 validates *all* its parameters as a
  method call and throws `HandlerMethodValidationException`, not `MethodArgumentNotValidException`.
  The status stayed 400 but `violations` was empty. Fixed in `common`: an override of
  `handleHandlerMethodValidationException` extracts body-field and parameter violations. Caught
  because the test asserted on the body, not just the status.
- **BUG-008: `TestRestTemplate` couldn't send PATCH.** The JDK `HttpURLConnection` it uses by
  default doesn't support PATCH (`ProtocolException: Invalid HTTP method`). Added Apache HttpClient 5
  (test scope); `TestRestTemplate` picks it up automatically.

## Design notes

- **FNOL isn't blocked on the policy check.** A loss report is accepted even if Policy Service is down;
  validation is asynchronous (Phases 4–5) and can reject it. See ADR-0004.
- **Aggregates reference each other by ID** (`policyId`, `adjusterId`), not JPA relationships.
- **History in the same transaction** as the change: there's never a status change without its audit row, and the reverse (ADR-0018).
- **Sort is fixed server-side** (`createdAt DESC`); client-controlled sort could hit unindexed columns or fail on unknown properties.

## Deferred

- Composite index `(policy_id, status, created_at DESC)` + `EXPLAIN ANALYZE` (Phase 7).
- Mapping optimistic-lock failures to 409 and a concurrent-update test (Phase 7). `@Version` is already on `Claim`.
- Publishing `ClaimSubmitted` / consuming validation and payment events (Phase 4).
