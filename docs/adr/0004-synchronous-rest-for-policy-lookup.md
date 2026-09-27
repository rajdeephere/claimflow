# ADR-0004: Synchronous REST for policy lookups

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

Validation needs the policy's validity dates, coverages, limits and deductible at the moment it
evaluates a claim. That's a query, not an event.

## Decision

Validation Service calls Policy Service over REST (`GET /api/v1/policies/{id}`), propagating the
correlation ID header, with short connect/read timeouts. Because validation itself runs
asynchronously (triggered by `ClaimSubmitted`), a Policy Service outage only delays validation and
never blocks claim intake. Failed lookups are retried by the Kafka consumer's retry policy.

## Consequences

- ✅ Always reads current policy data; no replicated copy to keep in sync.
- ✅ Simple and easy to reason about.
- ⚠️ Runtime dependency: validation stalls if Policy Service is down (mitigated by retry and the async trigger).

## Alternatives considered

- **Replicate policy data via events** (Validation keeps a local read model): removes the runtime
  dependency, but adds sync complexity and staleness; a reasonable future step at higher scale.
- **gRPC:** faster, but adds tooling; unnecessary at this load.
