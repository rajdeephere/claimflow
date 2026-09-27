# ADR-0002: Microservices with a database per service

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

The claims domain splits naturally along the same lines as Guidewire's products: policy
administration (PolicyCenter), claims handling (ClaimCenter) and billing/payments (BillingCenter).
Different teams would own each, and they change at different rates.

## Decision

Build separate Spring Boot services: **policy**, **claim**, **validation** and **payment**.
Each DB-backed service owns its own PostgreSQL database (`policy_db`, `claim_db`, `payment_db`).
No service reads another's tables; data is exchanged via REST or Kafka events. Cross-service
references are plain IDs with no foreign keys.

Locally, all three databases live on one Postgres server for convenience; the boundary is enforced
by separate databases, not separate servers.

## Consequences

- ✅ Services deploy, scale and evolve their schema independently.
- ✅ A failure in Payment doesn't take down claim intake.
- ✅ Maps cleanly onto the Guidewire product split, which is useful domain framing.
- ⚠️ No ACID transactions across services, so we need eventual consistency, idempotency and compensations.
- ⚠️ More moving parts: more config, more processes to run and monitor.
- ⚠️ Reporting across services needs events or a read model, not a SQL join.

## Alternatives considered

- **Modular monolith** (one deployable, package-per-module, one DB with schemas): simpler and a
  valid starting point; rejected because demonstrating service boundaries and messaging is a project goal.
- **Shared database:** couples services at the schema level; rejected.
