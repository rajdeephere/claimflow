-- Benchmark for the "claims of a policy by status, newest first" query (GET /api/v1/claims?policyId=&status=).
--
-- Run against a SCRATCH database, never claim_db:
--   docker exec claimflow-postgres psql -U claimflow -d postgres -c "CREATE DATABASE claim_bench"
--   docker exec -i claimflow-postgres psql -U claimflow -d claim_bench < infra/perf/claims-index-benchmark.sql
--
-- The two queries are exactly what Hibernate/Spring Data generate for a page of results (captured with
-- logging.level.org.hibernate.SQL=DEBUG): the page query, and the count query for totalElements.
-- Each EXPLAIN is run twice; the second (warm cache) run is the one to read.

\timing off
\pset pager off
SET max_parallel_workers_per_gather = 0;   -- single-process plans: easier to compare, like a busy OLTP server

DROP TABLE IF EXISTS claims;
DROP TABLE IF EXISTS bench_policies;

-- Same columns as claim_db.claims (Flyway V1 + V3) and the same indexes that exist before the tuning.
CREATE TABLE claims (
    id UUID PRIMARY KEY, claim_number VARCHAR(20) NOT NULL UNIQUE, policy_id UUID NOT NULL,
    loss_type VARCHAR(30) NOT NULL, incident_date DATE NOT NULL, reported_at TIMESTAMPTZ NOT NULL,
    description VARCHAR(2000) NOT NULL, claimed_amount NUMERIC(15,2) NOT NULL, approved_amount NUMERIC(15,2),
    status VARCHAR(30) NOT NULL, adjuster_id UUID, rejection_reason VARCHAR(500), idempotency_key VARCHAR(100) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    coverage_limit NUMERIC(15,2), deductible NUMERIC(15,2));
CREATE INDEX idx_claims_adjuster_id ON claims (adjuster_id);

-- 50,000 policies; policy #1 is a large fleet policy
CREATE TABLE bench_policies AS SELECT n, gen_random_uuid() AS id FROM generate_series(1, 50000) AS n;
ALTER TABLE bench_policies ADD PRIMARY KEY (n);

-- 480,000 claims spread over policies 2..50000 (~10 each) + 20,000 claims on fleet policy #1 = 500,000.
-- (A JOIN on the primary key, not a correlated subquery per row: the first version of this script
--  scanned bench_policies once per claim, 500k x 50k rows, and never finished.)
INSERT INTO claims
SELECT gen_random_uuid(), 'CLM-B-' || lpad(g::text, 9, '0'),
       p.id,
       'COLLISION', DATE '2023-01-01' + (g % 1000), now() - (random() * interval '3 years'), 'benchmark claim',
       1000 + (g % 500000), NULL,
       CASE WHEN r < 0.40 THEN 'CLOSED' WHEN r < 0.60 THEN 'SETTLED' WHEN r < 0.75 THEN 'UNDER_REVIEW'
            WHEN r < 0.85 THEN 'SUBMITTED' WHEN r < 0.95 THEN 'REJECTED' ELSE 'SETTLEMENT_PENDING' END,
       NULL, NULL, NULL, 0, now() - (random() * interval '3 years'), now(), 500000, 20000
FROM (SELECT g, random() AS r, CASE WHEN g <= 20000 THEN 1 ELSE 2 + (g % 49999) END AS pn
      FROM generate_series(1, 500000) AS g) s
JOIN bench_policies p ON p.n = s.pn;
ANALYZE claims;

SELECT count(*) AS total_claims, count(DISTINCT policy_id) AS policies,
       (SELECT count(*) FROM claims c JOIN bench_policies p ON p.id = c.policy_id WHERE p.n = 1) AS fleet_policy_claims
FROM claims;

SELECT id AS typical_policy FROM bench_policies WHERE n = 4242 \gset
SELECT id AS fleet_policy FROM bench_policies WHERE n = 1 \gset

-- ---------------------------------------------------------------------------------------------
\echo '################ A. NO INDEX ON policy_id ################'
\echo '--- A1 typical policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- A2 fleet policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- A3 fleet policy: count query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';

-- ---------------------------------------------------------------------------------------------
CREATE INDEX idx_claims_policy_only ON claims (policy_id);
ANALYZE claims;
\echo '################ B. SINGLE-COLUMN INDEX (policy_id) ################'
\echo '--- B1 typical policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- B2 fleet policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- B3 fleet policy: count query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';
DROP INDEX idx_claims_policy_only;

-- ---------------------------------------------------------------------------------------------
-- The index added by Flyway V4 in claim-service
CREATE INDEX idx_claim_policy_status_created ON claims (policy_id, status, created_at DESC);
ANALYZE claims;
\echo '################ C. COMPOSITE INDEX (policy_id, status, created_at DESC) ################'
\echo '--- C1 typical policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'typical_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- C2 fleet policy: page query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED' ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY;
\echo '--- C3 fleet policy: count query'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(c1_0.id) FROM claims c1_0 WHERE c1_0.policy_id = :'fleet_policy' AND c1_0.status = 'CLOSED';

SELECT pg_size_pretty(pg_relation_size('claims')) AS table_size,
       pg_size_pretty(pg_relation_size('idx_claim_policy_status_created')) AS composite_index_size;
