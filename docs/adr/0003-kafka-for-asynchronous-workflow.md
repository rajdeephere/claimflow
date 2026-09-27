# ADR-0003: Apache Kafka for asynchronous workflow events

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

Claim processing is a multi-step workflow across services (validate → review → pay). Steps should
not block the customer, must survive a service outage, and several consumers (payment, notification,
audit) care about the same events.

## Decision

Use **Apache Kafka** (Spring Kafka) as the event backbone. Services publish domain events
(`ClaimSubmitted`, `ClaimValidated`, `ClaimApproved`, `PaymentCompleted`, ...) and interested
services consume them in their own consumer groups. Run Kafka in **KRaft mode** (no ZooKeeper).
Topics are created explicitly; auto-creation is disabled.

## Consequences

- ✅ Temporal decoupling: the producer doesn't need the consumer to be up.
- ✅ Retention lets a consumer that was down catch up from its last committed offset.
- ✅ Fan-out: new consumers (e.g. analytics) can be added without changing producers.
- ✅ Partitioning by claimId keeps events for one claim in order.
- ⚠️ At-least-once delivery means consumers **must be idempotent** (ADR-0009).
- ⚠️ Eventual consistency: claim status lags the real outcome by a short time.
- ⚠️ Harder to debug than a direct call, so we need correlation IDs (ADR-0007) and a DLT.

## Alternatives considered

- **RabbitMQ / ActiveMQ / IBM MQ** (classic message brokers): excellent for task queues and
  routing, and IBM MQ is common in insurance integrations. A message is typically removed once
  consumed, so replay and multiple independent consumers are less natural. Kafka's log model fits
  "events many services react to" better. The patterns (idempotency, DLQ, correlation) carry over to any MQ.
- **Synchronous REST chain:** simple, but couples availability and latency of every service; rejected.
