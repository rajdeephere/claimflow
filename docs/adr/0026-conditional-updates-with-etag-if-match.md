# ADR-0026: Conditional updates with ETag / If-Match

- **Status:** Accepted (complements ADR-0011)
- **Date:** 2026-09-27
- **Phase:** 7

## Context

`@Version` (ADR-0011) only catches two transactions overlapping in time. It does **not** catch the
classic lost update across HTTP requests:

1. Adjuster A opens the claim (version 3) and reads it for a while.
2. Adjuster B changes it (version 4).
3. A submits a change based on what they saw at version 3.

Step 3's request loads the claim fresh (version 4), applies A's change and commits cleanly. B's
change is overwritten without anyone noticing, because A decided based on stale information.

## Decision

Standard HTTP conditional requests (RFC 9110):

- `GET /claims/{id}` returns **`ETag: "<version>"`**. Mutating endpoints (`PATCH /status`,
  `POST /assign-adjuster`) also return the new ETag.
- Clients may send **`If-Match: "<version>"`**. The service compares it with the current version
  before changing anything and returns **412 Precondition Failed** on a mismatch, with both versions in the message.
- `If-Match` is optional (`*` or absent means no check), so existing clients keep working.
- Two layers together:

| Layer | Catches | Response |
|---|---|---|
| If-Match check (service) | a user acting on a stale screen | 412 |
| `@Version` (commit) | two transactions at the same instant, including two that both passed the If-Match check | 409 |

## Consequences

- ✅ Verified: 10 concurrent requests with the same If-Match → exactly one 200, the rest 412/409, one history row.
- ✅ Verified live through the gateway (ETag and If-Match pass through Spring Cloud Gateway).
- ✅ Standard mechanism that UIs, API clients and caches understand.
- ⚠️ Only protects clients that send If-Match; a UI must be built to use it.
- ⚠️ The version leaks into the API contract (acceptable; it's opaque to clients).

## Alternatives considered

- **Version field in the request body** (`{"expectedVersion": 3, ...}`): works, but non-standard and mixes concerns.
- **Server-side "last read" tracking per user:** stateful and complex.
- **Make it mandatory (428 Precondition Required when missing):** stricter; a good option once all clients are updated.
