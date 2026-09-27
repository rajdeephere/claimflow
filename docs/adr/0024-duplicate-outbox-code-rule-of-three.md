# ADR-0024: Copy the outbox and processed-events code rather than sharing it (for now)

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 6

## Context

Payment Service needs the same transactional outbox (`OutboxEvent`, `OutboxRepository`,
`OutboxWriter`, `OutboxRelay`) and idempotency store (`ProcessedEventStore`) as Claim Service,
about 250 lines. The natural reflex is to move them into `common`.

Sharing them there would mean putting **JPA entities and repositories in `common`**. Each service
would then need `@EntityScan` / `@EnableJpaRepositories` over `com.claimflow`, and every service
using `common` would get the `OutboxEvent` entity. Policy Service has no outbox table, so with
`ddl-auto: validate` it would **fail at startup**. `common` would also gain a hard dependency on JPA
and Hibernate.

## Decision

Copy the classes into `payment-service` (package renamed; `SOURCE` differs). Both copies carry a
comment pointing to this ADR. Apply the **rule of three**: when a third service needs an outbox,
extract a dedicated `claimflow-outbox` library module (JPA-based, opt-in, with its own
auto-configuration and Flyway migration) instead of growing `common`.

## Consequences

- ✅ Services stay independently deployable; `common` stays free of persistence concerns.
- ✅ Each copy can evolve if a service's needs diverge (e.g. Payment could add outbox priorities).
- ⚠️ A bug fixed in one copy must be fixed in the other. Mitigated by identical tests on both sides and this ADR.

## Alternatives considered

- **Move into `common`:** breaks Policy Service's schema validation and couples every service to JPA; rejected.
- **A separate outbox library now:** the right shape eventually; premature with two users.
- **Debezium CDC instead of a polling relay:** removes the relay code entirely, but needs Kafka Connect infrastructure.
