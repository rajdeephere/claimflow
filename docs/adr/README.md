# Architecture Decision Records

Each ADR records one significant decision: context, decision, consequences and alternatives.
Accepted ADRs are immutable; a changed decision is recorded as a new ADR that supersedes the old one.
To add one, copy [`template.md`](template.md).

| # | Decision | Status | Phase |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 1 |
| [0002](0002-microservices-with-database-per-service.md) | Microservices with a database per service | Accepted | 1 |
| [0003](0003-kafka-for-asynchronous-workflow.md) | Apache Kafka for asynchronous workflow events | Accepted | 1 |
| [0004](0004-synchronous-rest-for-policy-lookup.md) | Synchronous REST for policy lookups | Accepted | 1 |
| [0005](0005-flyway-owns-schema.md) | Flyway owns the schema; Hibernate only validates | Accepted | 1 |
| [0006](0006-api-gateway-with-spring-cloud-gateway.md) | API Gateway with Spring Cloud Gateway | Accepted | 1 |
| [0007](0007-correlation-id-propagation.md) | Correlation ID propagation | Accepted | 1 |
| [0008](0008-claim-state-machine-as-domain-enum.md) | Claim state machine as a domain enum | Accepted | 3 |
| [0009](0009-idempotent-consumers-processed-events.md) | Idempotent Kafka consumers via a processed_events table | Proposed | 4 |
| [0010](0010-bigdecimal-for-money.md) | BigDecimal and NUMERIC for monetary values | Accepted | 1 |
| [0011](0011-optimistic-locking-on-claims.md) | Optimistic locking on claims | Proposed | 7 |
| [0012](0012-reliable-event-publishing.md) | Reliable event publishing (dual-write problem) | Proposed | 4 |
| [0013](0013-jwt-authentication-at-gateway.md) | JWT authentication at the gateway, roles in services | Proposed | 7 |
| [0014](0014-derive-policy-period-state-from-dates.md) | Derive policy period state from dates | Accepted | 2 |
| [0015](0015-standard-mvc-errors-via-responseentityexceptionhandler.md) | Standard MVC errors via ResponseEntityExceptionHandler | Accepted | 2 |
| [0016](0016-testcontainers-for-integration-tests.md) | Testcontainers (real PostgreSQL) for integration tests | Accepted | 2 |
| [0017](0017-idempotent-fnol-with-idempotency-key.md) | Idempotent FNOL with an Idempotency-Key header | Accepted | 3 |
| [0018](0018-append-only-claim-history.md) | Append-only claim history in the same transaction | Accepted | 3 |

**Status lifecycle:** Proposed → Accepted → (Deprecated | Superseded by ADR-XXXX)
