# Phase 2 — Policy Service

**Status:** ✅ Complete

## Goal

A working PolicyCenter-like service: customers, policies and coverages, with the rules that decide
whether a loss is covered. Validation Service will call it in Phase 5.

## Delivered

| Item | Location |
|---|---|
| Flyway `V1__create_policy_tables.sql`: `customers`, `policies`, `coverages`, `policy_number_seq`, constraints, index | `policy-service/src/main/resources/db/migration/` |
| Domain model: `Policy` (aggregate root), `Coverage`, `Customer`, `ProductType`, `CoverageType`, `PolicyStatus` | `policy-service/.../policy/`, `.../customer/` |
| REST API: customers, policies, cancel, coverage check | `CustomerController`, `PolicyController` |
| Bean Validation on request DTOs, including a cross-field rule (`endDate > startDate`) | `CreatePolicyRequest`, `CreateCustomerRequest` |
| Human-readable policy numbers from a DB sequence (`POL-2026-000001`) | `PolicyNumberGenerator` |
| `GlobalExceptionHandler` now extends `ResponseEntityExceptionHandler` | `common/` |
| 34 tests: domain, service (Mockito), web layer (MockMvc), integration (Testcontainers PostgreSQL) | `policy-service/src/test/` |

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/api/v1/customers` | 201 + `Location` | 400 invalid, 409 duplicate email |
| GET | `/api/v1/customers/{customerId}` | 200 | 404 |
| POST | `/api/v1/policies` | 201 + `Location` | 400 invalid, 422 unknown customer / coverage not offered / duplicate coverage / deductible ≥ limit |
| GET | `/api/v1/policies/{policyId}` | 200 | 400 bad UUID, 404 |
| GET | `/api/v1/customers/{customerId}/policies` | 200 (newest first) | 404 |
| POST | `/api/v1/policies/{policyId}/cancel` | 200 | 404, 409 already cancelled |
| GET | `/api/v1/policies/{policyId}/coverage-check?coverageType=&incidentDate=` | 200 `{covered, reason, limitAmount, deductible}` | 400 bad enum/date/missing param, 404 |

`coverage-check` reasons: `COVERED`, `POLICY_CANCELLED`, `OUTSIDE_POLICY_PERIOD`, `COVERAGE_NOT_ON_POLICY`.
"Not covered" is a normal **200** answer; the caller decides what it means for the claim.

## Business rules

| Rule | Enforced in | Also enforced by DB |
|---|---|---|
| End date after start date | `@AssertTrue` on the DTO (400) and the `Policy` constructor | `ck_policies_dates` |
| Coverage type must be offered by the product (MOTOR: collision, theft, third-party; HOME: fire, flood, theft; HEALTH: hospitalization) | `ProductType.allows()` → 422 | – |
| One coverage per type per policy | `Policy.addCoverage()` → 422 | `uk_coverages_policy_type` |
| Deductible < limit | `Policy.addCoverage()` → 422 | `ck_coverages_deductible` |
| Unique customer email (case-insensitive) | pre-check plus catching the constraint violation → 409 | `uk_customers_email` |
| Can't cancel twice | `Policy.cancel()` → 409 | – |
| In force on date D = ACTIVE and start ≤ D ≤ end | `Policy.isInForceOn()` | – |

## Verification

| Check | Result |
|---|---|
| `mvn test -pl policy-service -am` | ✅ 34 tests, 0 failures |
| Integration test on real PostgreSQL 16 (Testcontainers): full lifecycle, 409 duplicate email, 422 unknown customer, 422 coverage not offered | ✅ |
| Full `mvn install` of all modules (the `common` change doesn't break other services) | ✅ |
| Manual flow **through the gateway** (:8000): create customer, create policy, coverage check, bad enum, duplicate customer | ✅ |
| Correlation ID `demo-p2` sent to the gateway appears in the policy-service log line "Created policy POL-2026-000001" | ✅ |
| `GET /policies/{id}` issues **1 SQL query** (policy + coverages + customer joined); `GET /customers/{id}/policies` issues **2** whatever the number of policies, so no N+1 | ✅ checked with `logging.level.org.hibernate.SQL=DEBUG` |
| `cancel` increments `version` 0 → 1 (`@Version`) | ✅ |

## Issues found & fixed

- **BUG-006: bad client input returned 500.** A malformed UUID in the path, an unknown enum in a
  query parameter and a missing required parameter all fell through to the catch-all handler.
  This is the same class of bug as BUG-001. Instead of adding three more handlers,
  `GlobalExceptionHandler` now extends Spring's `ResponseEntityExceptionHandler`, which maps every
  standard MVC exception to its correct status, and only the body rendering is overridden. Tests
  added for 405 and 415 as well, to prove the whole class is fixed.

## Design notes

- **Expired isn't a stored status.** Only `ACTIVE` and `CANCELLED` are stored; being outside the
  policy period is derived from the dates, so no scheduled job is needed and the data can't go stale
  ([ADR-0014](../adr/0014-derive-policy-period-state-from-dates.md)).
- **Aggregate root.** Coverages are created only through `Policy.addCoverage()`, and `getCoverages()`
  returns an unmodifiable list, so the invariants can't be bypassed.
- **Referenced vs addressed resources.** An unknown `customerId` in the *body* is 422; an unknown ID in the *URL* is 404.
- **Testcontainers, not H2** ([ADR-0016](../adr/0016-testcontainers-for-integration-tests.md)).

## Deferred

- Policy search/pagination, endorsements (mid-term changes), premium calculation. Not needed by the claims flow.
