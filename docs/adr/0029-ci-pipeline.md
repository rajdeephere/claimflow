# ADR-0029: CI pipeline stages and the unit/integration test split

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 8

## Context

The spec's pipeline is *push → build → unit tests → integration tests → quality checks → Docker build → image*.
The test suite has 185 fast unit tests (no Docker) and 35 integration tests on Testcontainers
PostgreSQL and Kafka (minutes, need Docker). Running everything in one undifferentiated step gives
slow feedback and makes it hard to see *what kind* of test failed.

## Decision

- **Naming convention:** `*IntegrationTest` = needs containers. Surefire excludes them (`mvn test`);
  Failsafe runs them (`mvn verify`). JaCoCo instruments both and merges the reports.
- **GitHub Actions** (`.github/workflows/ci.yml`), on pushes to `main` and all pull requests:
  1. `mvn test`: build + unit tests (fails fast, ~1 min)
  2. `mvn verify -Dskip.unit=true`: integration tests + coverage (unit tests not repeated)
  3. job summary with test counts per module; upload coverage, and test reports on failure
  4. **Docker images, one matrix job per service, only if every test passed** (`needs:`), with the
     GitHub Actions layer cache (`cache-from/to: type=gha`)
- `concurrency` cancels superseded runs on the same branch; `permissions: contents: read` (least privilege).
- Images are **built, not pushed**, by default; pushing to GHCR on `main` is a documented opt-in.

## Consequences

- ✅ A broken unit test fails in about a minute, before any container starts.
- ✅ Integration tests run against real PostgreSQL 16 and the same Kafka image as production-like compose.
- ✅ No image is ever built from code that failed a test.
- ⚠️ Integration tests need Docker on the runner (true for `ubuntu-latest`; self-hosted runners need it installed).
- ⚠️ Not yet executed on GitHub: validated locally (YAML, summary script, both Maven steps); the first push is its first real run.

## Alternatives considered

- **One `mvn verify` step:** simpler, slower feedback, unit and integration failures mixed together.
- **Separate jobs for unit and integration tests:** parallel, but each job re-resolves dependencies and rebuilds; slower in total for this size.
- **Jenkins / GitLab CI:** equivalent stages; GitHub Actions chosen because the code lives on GitHub and needs no server.
