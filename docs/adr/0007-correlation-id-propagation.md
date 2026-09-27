# ADR-0007: Correlation ID propagation

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

A single business transaction (one claim) touches the gateway, three services and Kafka.
Without a shared identifier, logs from each service can't be tied together.

## Decision

- The **gateway** accepts an incoming `X-Correlation-ID` or generates a UUID, forwards it
  downstream and returns it to the client.
- Each **service** (`CorrelationIdFilter` in `common`) reads the header, generates one if it's
  missing (direct calls), puts it in the SLF4J **MDC**, echoes it back and clears the MDC in `finally`.
- The log pattern prints `[service,correlationId]`; `ApiError` includes it.
- *(Phase 4)* Producers copy it into a **Kafka header**; consumers restore it into the MDC before processing.
- Audit history rows store it.

## Consequences

- ✅ `grep <id>` across all logs reconstructs the whole flow.
- ✅ Support can ask a customer for the ID shown in an error response.
- ⚠️ The MDC is ThreadLocal: it must be explicitly propagated to async threads and Kafka
  consumers, and doesn't apply in the reactive gateway (the ID lives on the request there instead).

## Alternatives considered

- **OpenTelemetry / Micrometer Tracing:** full distributed tracing with spans and timings; the
  better production choice and the natural next step. The hand-rolled version was chosen first to
  understand the mechanics.
