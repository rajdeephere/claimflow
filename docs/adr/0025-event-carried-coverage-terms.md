# ADR-0025: Coverage terms travel with the events (event-carried state transfer)

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 6

## Context

Payment needs the claim's **deductible** and **coverage limit** to calculate the settlement.
Validation already fetched them from Policy Service and published them in `ClaimValidated`.

## Decision

- Claim Service stores `coverage_limit` and `deductible` on the claim when it processes
  `ClaimValidated` (`Claim.markValidated`, Flyway V3).
- `ClaimApproved` carries them (new optional fields; non-breaking per ADR-0019).
- Payment settles using the terms in the event, with **no call to Policy Service**.
- Claim Service uses the stored deductible for a new rule: the approved amount must exceed the
  deductible (otherwise nothing is payable, so the adjuster should reject instead). 422 otherwise.

## Consequences

- ✅ Payment has no runtime dependency on Policy Service; one less synchronous call and failure mode.
- ✅ The settlement uses the terms **as validated**. If the policy is changed afterwards, the claim
  is still settled on the terms that applied, which is also the right insurance behaviour (terms at the time of loss).
- ✅ The adjuster sees the terms on the claim (`GET /claims/{id}` shows `coverageLimit`, `deductible`).
- ⚠️ Claims validated before Phase 6 have no stored terms; their `ClaimApproved` goes to the DLT
  in Payment ("no coverage terms"), which is correct: a human must decide.
- ⚠️ Data is duplicated across services (a snapshot, by design).

## Alternatives considered

- **Payment calls Policy Service's coverage-check:** always current, but adds a synchronous dependency
  and would use *today's* policy state rather than the validated one.
- **Payment keeps its own read model of policies from policy events:** heavy for one lookup.
