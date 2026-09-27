# ADR-0014: Derive policy period state from dates, store only explicit statuses

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 2

## Context

A policy can be active, cancelled, not yet started, or expired. Storing all four as a `status`
column means something must flip `ACTIVE → EXPIRED` at midnight on the end date (a scheduled job).
If the job fails or runs late, claims would be validated against a wrong status.

## Decision

Store only statuses that result from an explicit **action**: `ACTIVE` and `CANCELLED`.
Whether a policy covers a date is **derived**: `Policy.isInForceOn(date)` =
`status == ACTIVE && startDate <= date <= endDate` (both ends inclusive).
Validation always asks "in force **on the incident date**", not "active now", which is also the
correct insurance question: a claim filed today for an incident last month depends on last month.

## Consequences

- ✅ No batch job; the state can never be stale.
- ✅ Answers historical questions correctly (incident date ≠ today).
- ⚠️ "List all expired policies" becomes a date query (`end_date < current_date`) instead of a status filter; fine with an index if needed.

## Alternatives considered

- **Stored EXPIRED status + nightly job:** common in legacy systems; rejected for the staleness risk.
- **Computed column / DB view:** possible, but puts domain logic in SQL; the Java method is easier to unit test.
