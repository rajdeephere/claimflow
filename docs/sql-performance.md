# SQL Performance: claims by policy and status

> Reproduce: `infra/perf/claims-index-benchmark.sql` (instructions in its header). Runs in ~7 s on a
> scratch database. Numbers below are the **warm-cache** run on PostgreSQL 16 (Docker, laptop),
> `max_parallel_workers_per_gather = 0`.

## The query

`GET /api/v1/claims?policyId=&status=&page=&size=` → `ClaimRepository.findByPolicyIdAndStatus(..., Pageable)`.
Captured from Hibernate with `logging.level.org.hibernate.SQL=DEBUG`:

```sql
-- page query
SELECT c1_0.id, ... FROM claims c1_0
WHERE c1_0.policy_id = ? AND c1_0.status = ?
ORDER BY c1_0.created_at DESC
FETCH FIRST ? ROWS ONLY;

-- count query (Spring Data runs it for totalElements, except when the first page isn't full)
SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = ? AND c1_0.status = ?;
```

## Data set

| | |
|---|---|
| claims | 500,000 (89 MB table) |
| policies | 50,000 (~10 claims each) + one **fleet policy** with 20,000 claims |
| statuses | CLOSED 40 %, SETTLED 20 %, UNDER_REVIEW 15 %, SUBMITTED 10 %, REJECTED 10 %, SETTLEMENT_PENDING 5 % |
| query | `status = 'CLOSED'`, first page of 20 |

## Results

### Page query

| Index | Typical policy (3 matches) | Fleet policy (7,911 matches) | Plan (fleet) |
|---|---|---|---|
| **A.** none | 47.7 ms, 11,357 buffers | **49.9 ms**, 11,357 buffers | Seq Scan, 492,089 rows removed by filter, top-N heapsort |
| **B.** `(policy_id)` | 0.086 ms, 12 buffers | **3.7 ms**, 473 buffers | Bitmap Index Scan (20,000 rows), 12,089 removed by filter, top-N heapsort |
| **C.** `(policy_id, status, created_at DESC)` | **0.040 ms**, 6 buffers | **0.079 ms**, 23 buffers | **Index Scan, 20 rows read, no filter, no sort** |

**Fleet policy: ~630× faster than no index, ~47× faster than the single-column index.**

### Count query (fleet policy)

| Index | Time | Plan |
|---|---|---|
| none | 49.4 ms | Seq Scan |
| `(policy_id)` | 3.8 ms | Bitmap scan of 20,000 rows, then filter |
| composite | **1.9 ms** | Bitmap Index Scan (exactly 7,911 rows), Bitmap Heap Scan |

The composite index is 26 MB (29 % of the table).

## Why the composite index wins

1. **Equality columns first** (`policy_id`, `status`): the index jumps straight to the matching entries.
2. **The sort column last, in the query's direction** (`created_at DESC`): matching entries are
   *already* in the required order, so there's no Sort node and the scan **stops after 20 rows**
   (it reads 20, not 7,911).
3. `(policy_id)` alone narrows the rows but must still fetch **all** of a policy's claims, filter by
   status, and sort. Fine for 10 claims per policy, 47× slower for a fleet policy. **Test with skewed data**, not just averages.
4. A single-column index on `status` would be nearly useless: 6 values, ~40 % selectivity, so the
   planner prefers a sequential scan.

## Remaining cost: the count query

The count still visits 7,911 table rows (1.9 ms), because Spring Data counts `c1_0.id`, which isn't in
the index, and PostgreSQL must check row visibility in the heap anyway. Options if it mattered:

| Option | Trade-off |
|---|---|
| Return a `Slice` (has-next) instead of a `Page` | no count query at all; the UI shows "next" instead of "page 3 of 400" |
| `count(*)` + a vacuumed table | allows an Index Only Scan via the visibility map |
| Cache or estimate totals | fine for dashboards, not exact |

Not done: 1.9 ms for the worst-case policy is acceptable here.

## Guarding it

`ClaimQueryPlanIntegrationTest` runs `EXPLAIN` on the migrated schema (with `enable_seqscan = off`)
and asserts the plan uses `idx_claim_policy_status_created` **without a Sort**, so changing the query's
sort or filter, or dropping the index, fails the build.

## Production notes

- On a large live table use `CREATE INDEX CONCURRENTLY` (no write lock). It can't run inside a
  transaction, so Flyway needs it in its own migration with transactions disabled.
- Check real plans with `EXPLAIN (ANALYZE, BUFFERS)` against production-like data, and use
  `pg_stat_statements` to find which queries actually cost the most.
- Every index slows writes and takes space; add them for measured, frequent queries, not speculatively.
