# ADR-0016: Testcontainers (real PostgreSQL) for integration tests, not H2

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 2

## Context

Integration tests need a database. An in-memory H2 is fast but is a *different database*: different
SQL dialect, different constraint and sequence behaviour, and it can't run our PostgreSQL Flyway
migrations reliably (`TIMESTAMPTZ`, `CREATE SEQUENCE ... nextval`, partial indexes later).

## Decision

Integration tests (`@SpringBootTest`) start **PostgreSQL 16 in Docker via Testcontainers**, wired
with Spring Boot's `@ServiceConnection`. They run the real Flyway migrations. The test pyramid:

| Layer | Tool | Speed | Checks |
|---|---|---|---|
| Domain unit | JUnit 5 | ms | business rules on entities/enums |
| Service unit | JUnit 5 + Mockito | ms | orchestration, error paths, "never saves on failure" |
| Web slice | `@WebMvcTest` + MockMvc | ~1 s | validation, status codes, error body, JSON shape |
| Integration | `@SpringBootTest` + Testcontainers | ~10 s | migrations, constraints, JPA mappings, full HTTP flow |

## Consequences

- ✅ Tests exercise the same DB engine and migrations as production.
- ✅ Constraint behaviour (unique email race, CHECKs) is actually tested.
- ⚠️ Needs Docker on dev machines and CI runners (GitHub Actions `ubuntu-latest` has it).
- ⚠️ Slower than H2 (container start ~5–10 s); mitigated by keeping most tests in the fast layers.

## Alternatives considered

- **H2 in PostgreSQL mode:** fast, but an imitation; a green build could hide real failures.
- **Shared dev database:** tests interfere with each other and with manual testing; not reproducible.
