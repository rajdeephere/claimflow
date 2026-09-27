# ClaimFlow — Event-Driven Insurance Claims Platform

A simplified insurance claims platform (FNOL → validation → review → settlement → payment → closure)
built with Java 17, Spring Boot 3, PostgreSQL and Apache Kafka. Inspired by the concepts behind
Guidewire PolicyCenter / ClaimCenter / BillingCenter; a learning implementation only.

## Modules

| Module               | Port | Database     | Role                                                        |
|----------------------|------|--------------|-------------------------------------------------------------|
| `api-gateway`        | 8000 | –            | Single entry point: routing, correlation ID, 503/504 errors |
| `common`             | –    | –            | Correlation ID filter, error model, global exception handler |
| `policy-service`     | 8081 | `policy_db`  | Policies, customers, coverages (PolicyCenter-like)          |
| `claim-service`      | 8082 | `claim_db`   | FNOL, claim lifecycle state machine, history (ClaimCenter)  |
| `validation-service` | 8083 | none         | Stateless rules: eligibility, coverage, dates, amounts      |
| `payment-service`    | 8084 | `payment_db` | Settlement calculation, idempotent payments (BillingCenter) |

## Run it

### Option A: everything in Docker (one command)

```bash
docker compose --profile apps up -d --build --wait    # infra + 5 services, ~1 min after the first build
```
API: `http://localhost:8000/api/v1/...` (only the gateway is published). Kafka UI: `http://localhost:8090`.

### Option B: infrastructure in Docker, services from your IDE / terminal

```bash
docker compose up -d                  # Postgres (host 5433), Kafka (9092), Kafka UI (8090)
mvn clean install                     # build + all tests (integration tests need Docker)
java -jar policy-service/target/policy-service-0.1.0-SNAPSHOT.jar      # :8081
java -jar claim-service/target/claim-service-0.1.0-SNAPSHOT.jar        # :8082
java -jar validation-service/target/validation-service-0.1.0-SNAPSHOT.jar  # :8083
java -jar payment-service/target/payment-service-0.1.0-SNAPSHOT.jar    # :8084
java -jar api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar            # :8000
```

### A claim, end to end

```bash
# 1. FNOL (policyId from POST /api/v1/policies)                      -> 201, SUBMITTED
curl -X POST localhost:8000/api/v1/claims -H 'Content-Type: application/json' -H 'X-Correlation-ID: demo-1'   -d '{"policyId":"<id>","lossType":"COLLISION","incidentDate":"2026-09-27","description":"Rear-ended","claimedAmount":200000.00}'
# 2. automatic validation                                            -> UNDER_REVIEW within ~1 s
# 3. POST /api/v1/claims/{id}/assign-adjuster, then
#    PATCH /api/v1/claims/{id}/status {"targetStatus":"APPROVED","approvedAmount":200000.00}
# 4. automatic settlement and payment                                -> SETTLED, paid 180000.00
curl localhost:8000/api/v1/payments?claimId=<id>
curl localhost:8000/api/v1/claims/<id>/history                       # full audit trail
```

- Swagger UI per service: `http://localhost:808x/swagger-ui/index.html` (Option B)
- Send `X-Correlation-ID` on any request: it is echoed back and appears in every service's logs.

> Postgres is mapped to host port **5433** and the gateway runs on **8000** because 5432 and 8080
> are already used on the dev machine.

## Tests and CI

```bash
mvn test                       # 185 unit tests, no Docker needed (~45 s)
mvn verify                     # + 35 integration tests on Testcontainers PostgreSQL & Kafka, + JaCoCo coverage
```

GitHub Actions (`.github/workflows/ci.yml`) on every push and pull request:
**build → unit tests → integration tests → coverage → Docker image per service** (images only built
if all tests pass). Test counts are written to the job summary; coverage and failed-test reports are
uploaded as artifacts.

## Documentation

- [Architecture](docs/architecture.md): components, routes, ports
- [System design](docs/system-design.md): requirements, flows, data model, events, failure handling
- [Database design](docs/database-design.md) · [API design](docs/api-design.md)
- [Kafka events](docs/kafka-events.md) · [Failure handling](docs/failure-handling.md) · [SQL performance](docs/sql-performance.md)
- [Architecture Decision Records](docs/adr/README.md)
- Phase notes: [docs/phases/](docs/phases/)

## Roadmap

- [x] [Phase 1 — Foundation](docs/phases/phase-01-foundation.md): multi-module Maven, common module, Docker Compose, Flyway
- [x] [Phase 2 — Policy Service](docs/phases/phase-02-policy-service.md): customers, policies, coverages, coverage check
- [x] [Phase 3 — Claim Service](docs/phases/phase-03-claim-service.md): FNOL, state machine, audit trail, adjusters
- [x] [Phase 4 — Kafka](docs/phases/phase-04-kafka.md): transactional outbox, idempotent consumers, retry → DLT, correlation headers
- [x] [Phase 5 — Validation Service](docs/phases/phase-05-validation-service.md): rules engine, policy coverage check, outage-tolerant retries
- [x] [Phase 6 — Payment Service](docs/phases/phase-06-payment-service.md): settlement, idempotent payments, full lifecycle end to end
- [x] [Phase 7 — Concurrency & SQL performance](docs/phases/phase-07-enterprise.md): @Version 409, ETag/If-Match 412, composite index (49.9 ms → 0.079 ms). JWT deferred
- [x] [Phase 8 — Docker & CI](docs/phases/phase-08-docker-ci.md): layered non-root images, compose `apps` profile, GitHub Actions pipeline
- [x] Phase 9 — Docs: maintained phase by phase (architecture, system design, 29 ADRs, database, API, events, failure handling, SQL performance)
