# ADR-0027: Composite index for "claims of a policy by status, newest first"

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 7

## Context

`GET /api/v1/claims?policyId=&status=` is the adjuster's main work-queue query. Without an index it's
a full table scan (49.9 ms at 500k rows, growing linearly). The obvious fix, an index on `policy_id`,
is fast for typical policies but still fetches, filters and sorts **every** claim of a large fleet
policy (3.7 ms for 20k claims, growing with the policy's size).

## Decision

Flyway V4: `CREATE INDEX idx_claim_policy_status_created ON claims (policy_id, status, created_at DESC)`.

- Equality columns first, then the ORDER BY column in its direction: an ordered index scan that
  stops after the page size. **0.079 ms** for the fleet policy, no sort (see `docs/sql-performance.md`).
- Sorting is fixed server-side (`createdAt DESC`, Phase 3) *because* an index can only serve the orders it was built for.
- `ClaimQueryPlanIntegrationTest` asserts the plan keeps using the index without a Sort.

## Consequences

- ✅ ~630× faster than no index, ~47× faster than `(policy_id)` on skewed data; flat as tables grow.
- ⚠️ 26 MB at 500k rows (29 % of the table) and slightly slower inserts/updates of those columns.
- ⚠️ The count query still visits heap rows (1.9 ms worst case); a `Slice` or `count(*)` index-only scan is the next step if needed.

## Alternatives considered

- **`(policy_id)` only:** simpler, but degrades on large policies; measured and rejected.
- **`(status, policy_id, created_at)`:** also correct for this exact query, but can't serve "all claims of a policy" (`policy_id` alone) as a prefix.
- **Partial indexes per status** (`WHERE status = 'UNDER_REVIEW'`): smaller, useful for one hot status queue; not needed with one composite index.
