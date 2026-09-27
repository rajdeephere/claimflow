# ADR-0008: Claim state machine as a domain enum

- **Status:** Proposed
- **Date:** 2026-09-27
- **Phase:** 3

## Context

Claim status must follow a strict lifecycle (SUBMITTED → UNDER_REVIEW → APPROVED → ... → CLOSED).
Status changes come from REST calls and Kafka events; an invalid change (e.g. `CLOSED → APPROVED`)
must be impossible.

## Decision

Model the lifecycle as a `ClaimStatus` enum where each constant declares its allowed next states,
plus a single `Claim.transitionTo(newStatus, actor)` method that checks the transition, records a
`claim_history` row, and throws `InvalidStateTransitionException` (mapped to **409**) otherwise.
The Claim Service is the **only writer** of claim status.

## Consequences

- ✅ Rules live in one small, pure, unit-testable place (easy table-driven tests).
- ✅ No framework to learn or configure.
- ⚠️ Guards and side effects must be coded by hand if the lifecycle grows complex.

## Alternatives considered

- **Spring Statemachine:** powerful (guards, actions, persistence), but heavy for ~9 states.
- **Status checks scattered in service methods:** error-prone; rejected.
