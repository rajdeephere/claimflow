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

## Run locally

```bash
docker compose up -d          # Postgres (host port 5433), Kafka (9092), Kafka UI (http://localhost:8090)
mvn clean install             # build + test all modules (integration tests need Docker)
java -jar policy-service/target/policy-service-0.1.0-SNAPSHOT.jar
java -jar api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```

Call services through the gateway: `http://localhost:8000/api/v1/...`

- Health: `GET http://localhost:8081/actuator/health`
- Swagger UI: `http://localhost:8081/swagger-ui/index.html`
- Send `X-Correlation-ID: <id>` on any request; it is echoed back and printed on every log line.

> Postgres is mapped to host port **5433** and the gateway runs on **8000** because 5432 and 8080
> are already used on the dev machine.

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
- [ ] Phase 8 — Tests (JUnit, Mockito, Testcontainers) & GitHub Actions CI
- [ ] Phase 9 — Docs
