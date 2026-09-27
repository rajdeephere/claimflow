# ADR-0005: Flyway owns the schema; Hibernate only validates

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

The schema must evolve safely across environments, with changes reviewed like code.
Hibernate's `ddl-auto=update` can't express renames or data migrations and silently diverges.

## Decision

Every DB-backed service keeps versioned SQL migrations in `src/main/resources/db/migration`
(`V1__...sql`, `V2__...sql`), run by Flyway at startup. JPA runs with `ddl-auto: validate`, so a
mismatch between entities and schema fails fast at startup. Applied migrations are never edited.
`open-in-view` is disabled.

## Consequences

- ✅ Repeatable, auditable schema history (`flyway_schema_history`).
- ✅ Same migrations run in tests (Testcontainers), locally and in CI.
- ⚠️ Developers write DDL by hand.

## Alternatives considered

- **Hibernate `ddl-auto=update`:** convenient but unsafe beyond prototypes; rejected.
- **Liquibase:** equally valid (XML/YAML changelogs, rollback support); Flyway chosen for plain-SQL simplicity.
