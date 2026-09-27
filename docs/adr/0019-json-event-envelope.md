# ADR-0019: JSON event envelope with String serialisation

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 4

## Context

Services must agree on what a message looks like, evolve it independently, and let consumers
dedupe and trace it. Spring Kafka's `JsonSerializer` adds a `__TypeId__` header containing the
producer's **Java class name** (e.g. `com.claimflow.claim...ClaimSubmitted`), so consumers must have
that exact class, or configure type mappings. That couples services at the class level.

## Decision

- Every message value is an `EventEnvelope` serialised as JSON by our own `ObjectMapper`, sent with
  Kafka's plain **`StringSerializer`**, so no type headers.
- Envelope fields: `eventId, eventType, version, occurredAt, source, aggregateId, correlationId, payload`.
- Consumers read the envelope and dispatch on the **`eventType` string**, then map `payload`
  (a `JsonNode`) to their own class.
- Headers repeat `X-Correlation-ID`, `eventType`, `eventId` for tooling and tracing.
- **Key = `aggregateId` (claimId)** for per-claim ordering.
- **Evolution rules:** adding optional fields is non-breaking (consumers ignore unknown fields);
  renaming or removing fields needs a `version` bump or a new event type; consumers ignore unknown
  event types (tolerant reader).
- Payload records live in `common` as the shared **contract** (names and shapes), not shared domain logic.

## Consequences

- ✅ No Java class names on the wire; a consumer in another language could read it.
- ✅ Human-readable in Kafka UI and the console consumer; easy to replay by hand.
- ⚠️ No schema enforcement at publish time; a producer bug surfaces at the consumer (→ DLT).
- ⚠️ JSON is larger than binary formats (irrelevant at this volume).

## Alternatives considered

- **Avro / Protobuf + Schema Registry:** enforced schemas and compatibility checks, compact. The
  production-grade choice at scale; adds Schema Registry infrastructure. The envelope maps to it cleanly later.
- **CloudEvents spec:** a standard envelope with similar fields (`id, type, source, time, subject`);
  our envelope is deliberately close to it.
- **Spring `JsonSerializer` with type mappings:** works, but ties the contract to configuration on both sides.
