# Phase 4 — Kafka: events, outbox, idempotent consumers, DLT

**Status:** ✅ Complete

## Goal

Make the Claim Service event-driven and reliable: publish its events without the dual-write problem,
consume validation and payment outcomes exactly once *in effect*, and never let a bad message block processing.

## Delivered

| Item | Location |
|---|---|
| Event contract: `EventEnvelope`, `Topics`, `EventTypes`, payload records | `common/.../events/` |
| Shared Kafka setup: correlation `RecordInterceptor`, `DefaultErrorHandler` (retry → DLT), topic declarations | `common/.../kafka/` |
| Money-safe Jackson settings for every service | `common/.../config/JsonConfig.java` |
| Flyway V2: `outbox_events` (+ partial index), `processed_events` | `claim-service/.../db/migration/V2__*.sql` |
| Transactional outbox: `OutboxWriter` (same tx), `OutboxRelay` (`FOR UPDATE SKIP LOCKED`, ordered, at-least-once) | `claim-service/.../outbox/` |
| Idempotent consumer: `ProcessedEventStore` (`INSERT … ON CONFLICT DO NOTHING`), `InboundEventHandler`, `ClaimEventListener` | `claim-service/.../messaging/` |
| Events emitted: ClaimSubmitted, ClaimApproved, ClaimRejected, ClaimClosed | `ClaimService.publish` |
| Events consumed: ClaimValidated / Failed, PaymentInitiated / Completed / Failed | `InboundEventHandler` |
| Approval now also hands off to payment (APPROVED → SETTLEMENT_PENDING in the same tx) | `ClaimService.updateStatus` |
| Testcontainers Kafka (`apache/kafka:3.8.0`, same as compose) + singleton containers | `AbstractIntegrationTest` |

See [kafka-events.md](../kafka-events.md) for the catalogue and [failure-handling.md](../failure-handling.md) for failure modes.

## Verification

| Check | Result |
|---|---|
| `mvn install` (all modules) | ✅ 154 tests (common 3, policy 34, claim 117), 0 failures |
| FNOL → `ClaimSubmitted` on `claim.events` with the HTTP correlation ID as a header; outbox row marked published | ✅ integration + manual |
| Same event twice → processed once (1 `processed_events` row, 1 history row) | ✅ integration + manual |
| `ClaimValidationFailed` → REJECTED → `ClaimRejected` published **with the incoming event's correlation ID** | ✅ integration |
| Invalid-transition event → DLT immediately (no retries), cause in headers, nothing written | ✅ integration |
| Malformed JSON → DLT; the next valid event for the same claim is still processed | ✅ integration + manual |
| Full lifecycle over Kafka: validated → approved (REST) → `ClaimApproved` → payment events → SETTLED | ✅ integration |
| Amounts exact on the wire: `"claimedAmount":200000.00` | ✅ integration + manual |
| **Kafka stopped**: FNOL still 201; event held in the outbox (2 attempts); published automatically after Kafka restarted | ✅ manual |

## Issues found & fixed

- **BUG-009: event amounts lost their scale.** `200000.00` became `2E+5` inside event payloads:
  Jackson strips trailing zeros when building a `JsonNode`, writes such values in scientific notation,
  and reads decimals as `double`. Fixed with `JsonConfig` (exact decimal nodes,
  `USE_BIG_DECIMAL_FOR_FLOATS`, `WRITE_BIGDECIMAL_AS_PLAIN`) for every service. `JsonConfigTest`
  documents the default behaviour, measured: scale is lost, and value precision too beyond
  ~17 significant digits, which our `NUMERIC(15,2)` stays within.
- **BUG-010: retry/DLT logs hid the root cause.** Every failure logged Spring's wrapper message
  ("Listener method … threw exception"). The retry listener now logs the root cause on each attempt
  and when a record is sent to the DLT.

## Decisions

- [ADR-0009](../adr/0009-idempotent-consumers-processed-events.md): idempotent consumers → **Accepted**
- [ADR-0012](../adr/0012-reliable-event-publishing.md): transactional outbox → **Accepted**
- [ADR-0019](../adr/0019-json-event-envelope.md): JSON envelope with String serialisation (new)
- [ADR-0020](../adr/0020-exact-decimal-json.md): exact-decimal JSON settings (new)

## Deferred

- Validation Service producing real `validation.events` (Phase 5) and Payment Service (Phase 6);
  until then, integration tests and the CLI play those roles.
- Outbox cleanup job (delete published rows after N days); DLT replay tooling; consumer lag metrics.
