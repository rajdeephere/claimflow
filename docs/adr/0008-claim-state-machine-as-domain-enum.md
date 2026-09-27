# ADR-0008: Claim state machine as a domain enum

- **Status:** Accepted (Proposed in Phase 1; accepted with the trigger-source refinement when implemented in Phase 3)
- **Date:** 2026-09-27
- **Phase:** 3

## Context

Claim status must follow a strict lifecycle (SUBMITTED → UNDER_REVIEW → APPROVED → ... → CLOSED).
Status changes come from two kinds of callers: **people** via REST (an adjuster approves) and
**other services** via events (validation passed, payment completed). An invalid change
(e.g. `CLOSED → APPROVED`) must be impossible, and a person must not be able to fake a system
outcome (e.g. mark a claim `SETTLED` through the API).

## Decision

- `ClaimStatus` holds a single **transition table**, `from → (to → TransitionSource)`, built in a
  static block (an enum constructor can't reference constants declared later).
- `TransitionSource` is `USER` or `SYSTEM`; each allowed transition declares who may trigger it.
- `Claim` has **no `setStatus`**. Changes go through `transitionTo(target, source, details)` and the
  intention-revealing `approve`, `reject`, `close`, which add their own guards (adjuster present,
  amount ≤ claimed, reason required) *after* checking the transition is valid.
- A transition not in the table throws `InvalidStateTransitionException` (**409**). A USER asking
  for a SYSTEM transition throws `BusinessRuleException` (**422**).
- The Claim Service is the **only writer** of claim status; other services publish events.
- `allowedNext()` is exposed in API responses.

## Consequences

- ✅ The whole lifecycle is visible in ~10 lines and tested exhaustively (all 64 pairs, reachability, no dead ends).
- ✅ The system/user distinction prevents API callers from forging payment or validation outcomes.
- ✅ No framework dependency; pure Java, millisecond tests.
- ⚠️ Guards and side effects are hand-coded; if the lifecycle grows to dozens of states with
  complex guards, a workflow engine may pay off.

## Alternatives considered

- **Spring Statemachine:** guards, actions, persistence, but heavy configuration for 8 states and harder to read.
- **A workflow/BPM engine (Camunda, Temporal):** right for long-running human workflows with
  timers and escalations; overkill here. Guidewire itself uses configurable workflows for this.
- **Status checks scattered in service methods:** error-prone and untestable as a whole; rejected.
