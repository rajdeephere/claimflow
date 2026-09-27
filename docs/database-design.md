# Database Design

> Living document, one section per service, filled in as each service is built.
> Principles: database per service ([ADR-0002](adr/0002-microservices-with-database-per-service.md)),
> Flyway-owned schema ([ADR-0005](adr/0005-flyway-owns-schema.md)), money as `NUMERIC(15,2)`
> ([ADR-0010](adr/0010-bigdecimal-for-money.md)).

## Conventions

| Convention | Why |
|---|---|
| `UUID` primary keys generated in Java | IDs are known before insert; no sequence round-trip; safe to expose in URLs and events |
| `TIMESTAMPTZ` for instants, `DATE` for business dates | Policy periods and incident dates are calendar dates, not points in time |
| Enums stored as `VARCHAR` + `CHECK` constraint | Readable in SQL; reordering the Java enum can't corrupt data (never `ORDINAL`) |
| Constraints mirror API validation | The DB is the last line of defence if a bug or another path bypasses the service |
| Explicit indexes on FK columns used in lookups | PostgreSQL does **not** index foreign keys automatically |
| `version BIGINT` on aggregates that are updated | JPA optimistic locking |

---

## policy_db (Policy Service): Phase 2

```mermaid
erDiagram
    customers ||--o{ policies : holds
    policies ||--|{ coverages : has

    customers {
        uuid id PK
        varchar first_name
        varchar last_name
        varchar email UK
        varchar phone
        date date_of_birth
        timestamptz created_at
        timestamptz updated_at
    }
    policies {
        uuid id PK
        varchar policy_number UK "POL-2026-000001"
        uuid customer_id FK
        varchar product_type "MOTOR | HOME | HEALTH"
        varchar status "ACTIVE | CANCELLED"
        date start_date
        date end_date
        numeric premium "15,2"
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    coverages {
        uuid id PK
        uuid policy_id FK
        varchar coverage_type
        numeric limit_amount "15,2"
        numeric deductible "15,2"
    }
```

### Constraints

| Constraint | Rule |
|---|---|
| `uk_customers_email` | one customer per email (stored lower-case) |
| `uk_policies_policy_number` | policy numbers are unique |
| `ck_policies_dates` | `end_date > start_date` |
| `ck_policies_premium` | `premium > 0` |
| `ck_policies_product`, `ck_policies_status` | allowed enum values |
| `uk_coverages_policy_type` | one coverage of each type per policy |
| `ck_coverages_limit`, `ck_coverages_deductible` | `limit > 0`, `0 ≤ deductible < limit` |
| `coverages.policy_id … ON DELETE CASCADE` | coverages belong to their policy |

### Indexes

| Index | Serves |
|---|---|
| `idx_policies_customer_id` | `GET /customers/{id}/policies` |
| `uk_coverages_policy_type (policy_id, coverage_type)` | joining coverages by `policy_id` (leading column); no separate index needed |

### Sequence

`policy_number_seq` feeds `POL-<year>-<6 digits>`. A sequence is atomic, so concurrent inserts never
collide. Gaps (from rolled-back transactions) are acceptable for a reference number.

### Query behaviour (verified)

| Endpoint | SQL statements |
|---|---|
| `GET /policies/{id}` | 1 (policies ⟕ coverages ⟕ customers via `@EntityGraph`) |
| `GET /customers/{id}/policies` | 2 (customer exists check + one join query), constant regardless of policy count |

---

## claim_db (Claim Service): Phase 3

*To be designed.*

## payment_db (Payment Service): Phase 6

*To be designed.*
