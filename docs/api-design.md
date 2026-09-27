# API Design

> Living document, one section per service. All public endpoints are reached through the gateway
> at `http://localhost:8000`. Interactive docs: each service's `/swagger-ui/index.html`.

## Conventions

| Topic | Convention |
|---|---|
| Versioning | URI prefix `/api/v1` |
| Resources | Plural nouns: `/policies`, `/customers`, `/claims` |
| Sub-collections | `/customers/{id}/policies` |
| Business actions | `POST /{resource}/{id}/{action}` when the change has rules of its own (`/cancel`, `/assign-adjuster`), rather than a free-form `PATCH` of a status field |
| Create | `201 Created` + `Location` header + the created body |
| IDs | UUIDs in paths; human-readable numbers (`POL-2026-000001`) are attributes, not keys |
| Money | JSON numbers with 2 decimals, `BigDecimal` server-side |
| Dates | ISO-8601: `2026-03-10` (dates), `2026-09-27T07:56:44Z` (instants, UTC) |
| Tracing | `X-Correlation-ID` accepted on every request and always returned |

## Error model

Every service and the gateway return the same body:

```json
{
  "timestamp": "2026-09-27T07:56:45.439Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid value 'ALIENS' for 'coverageType'; allowed: [COLLISION, THEFT, ...]",
  "path": "/api/v1/policies/d265.../coverage-check",
  "correlationId": "26c566ea-b3d8-4889-aa90-718b876792fd",
  "violations": [ { "field": "premium", "message": "must be greater than or equal to 0.01" } ]
}
```

| Status | Meaning | Examples |
|---|---|---|
| 400 | The request is malformed or fails validation | missing field, bad UUID, unknown enum, bad date |
| 404 | The addressed resource doesn't exist | `GET /policies/{unknown}` |
| 405 / 415 | Wrong method / content type | `DELETE /policies/{id}` |
| 409 | Conflicts with current state | duplicate email, cancel twice, optimistic lock (Phase 7) |
| 422 | Well-formed, but breaks a business rule | unknown customer in body, FLOOD on a MOTOR policy |
| 500 | Our bug; details are logged, never returned | – |
| 503 / 504 | Gateway: downstream down / too slow | – |

---

## Policy Service: Phase 2

### Create customer
```http
POST /api/v1/customers
Content-Type: application/json

{ "firstName": "Priya", "lastName": "Sharma", "email": "priya@example.com",
  "phone": "+919876543210", "dateOfBirth": "1992-08-15" }
```
`201` → customer with `id`. `409` if the email exists (case-insensitive).

### Issue policy
```http
POST /api/v1/policies
Content-Type: application/json

{ "customerId": "bccf793c-...", "productType": "MOTOR",
  "startDate": "2026-01-01", "endDate": "2026-12-31", "premium": 12000.00,
  "coverages": [ { "coverageType": "COLLISION", "limitAmount": 500000.00, "deductible": 20000.00 } ] }
```
`201` →
```json
{ "id": "d2653a42-...", "policyNumber": "POL-2026-000001", "status": "ACTIVE", "version": 0,
  "coverages": [ { "coverageType": "COLLISION", "limitAmount": 500000.00, "deductible": 20000.00 } ], ... }
```

### Coverage check (used by Validation Service)
```http
GET /api/v1/policies/{policyId}/coverage-check?coverageType=COLLISION&incidentDate=2026-03-10
```
```json
{ "policyId": "d2653a42-...", "policyNumber": "POL-2026-000001", "coverageType": "COLLISION",
  "incidentDate": "2026-03-10", "covered": true, "reason": "COVERED",
  "limitAmount": 500000.00, "deductible": 20000.00 }
```
`reason` ∈ `COVERED | POLICY_CANCELLED | OUTSIDE_POLICY_PERIOD | COVERAGE_NOT_ON_POLICY`.

### Other endpoints
`GET /api/v1/customers/{id}` · `GET /api/v1/policies/{id}` · `GET /api/v1/customers/{id}/policies` ·
`POST /api/v1/policies/{id}/cancel`. See [phase-02-policy-service.md](phases/phase-02-policy-service.md) for status codes.

---

## Claim Service: Phase 3

### File a claim (FNOL)
```http
POST /api/v1/claims
Content-Type: application/json
Idempotency-Key: 5b1e0c3a-8d2f-4a8e-9c0b-1f2e3d4c5b6a
X-User-Id: agent-7

{ "policyId": "d2653a42-...", "lossType": "COLLISION", "incidentDate": "2026-03-10",
  "description": "Rear-ended at a signal", "claimedAmount": 200000.00 }
```
`201 Created` for a new claim; **`200 OK` with the same claim** when the key was seen before.
```json
{ "id": "...", "claimNumber": "CLM-2026-000001", "status": "SUBMITTED",
  "allowedNextStatuses": ["UNDER_REVIEW", "REJECTED"], "adjusterId": null, "version": 0, ... }
```

### Change status
```http
PATCH /api/v1/claims/{claimId}/status
{ "targetStatus": "APPROVED", "approvedAmount": 180000.00 }
{ "targetStatus": "REJECTED", "reason": "Damage pre-dates policy" }
{ "targetStatus": "CLOSED" }
```
| Situation | Status |
|---|---|
| Transition not in the lifecycle (e.g. CLOSED → APPROVED) | 409 with the allowed targets |
| Target is system-only (UNDER_REVIEW, SETTLED, ...) | 422 |
| Approve without an adjuster, or amount > claimed | 422 |
| Reject without a reason | 422 |

### Other endpoints
| Method | Path |
|---|---|
| GET | `/api/v1/claims/{claimId}` |
| GET | `/api/v1/claims?policyId=&status=&page=0&size=20` → `{content, page, size, totalElements, totalPages}`, newest first, size ≤ 100 |
| POST | `/api/v1/claims/{claimId}/assign-adjuster` `{ "adjusterId": "..." }` |
| GET | `/api/v1/claims/{claimId}/history` → `[{eventType, oldStatus, newStatus, performedBy, correlationId, details, timestamp}]` |
| POST / GET | `/api/v1/adjusters` |

---

## Payment Service: Phase 6

*To be designed.*
