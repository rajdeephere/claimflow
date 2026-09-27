# ADR-0018: Append-only claim history written in the same transaction

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 3

## Context

Insurance is regulated: for any claim we must be able to answer *what happened, when, by whom, and
as part of which request*. The audit record must never disagree with the claim itself.

## Decision

- A `claim_history` table: `claim_id, event_type, old_status, new_status, performed_by, correlation_id, details, created_at`.
- Every domain operation on `Claim` returns a `StatusChange`; `ClaimService.record(...)` inserts
  the history row **in the same database transaction** as the claim update, so both commit or neither does.
- Rows are append-only: the entity is `@Immutable` and every column is `updatable = false`.
- Non-status operations (adjuster assignment) are recorded too, with `old_status = new_status`.
- A failed operation (invalid transition, broken rule) writes **nothing**.

## Consequences

- ✅ A complete, ordered, attributable trail per claim; `GET /claims/{id}/history`.
- ✅ The correlation ID links a history row to the logs of every service involved.
- ⚠️ The application can't update rows, but a DBA could. In production, also revoke UPDATE/DELETE on
  the table for the app's DB role, or use a WORM store.
- ⚠️ The history table grows without bound; partition or archive by date at scale.

## Alternatives considered

- **Hibernate Envers:** automatic row-level versioning, but it captures *column diffs*, not
  *business events* ("CLAIM_APPROVED by adj-ravi").
- **Event sourcing** (state rebuilt from events): powerful, but a big complexity jump; the history
  table gives most of the audit value with a normal CRUD model.
- **Writing history asynchronously via Kafka:** it could be lost or lag behind the claim; rejected for the primary trail.
