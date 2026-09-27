# ADR-0006: API Gateway with Spring Cloud Gateway

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

Clients would otherwise need to know every service's host and port, and cross-cutting concerns
(authentication, correlation IDs, consistent errors when a service is down) would be duplicated in
each service. The first scope plan deferred a gateway; with four services exposed it now clearly
pays for itself.

## Decision

Add an `api-gateway` module using **Spring Cloud Gateway** (reactive, Netty) on port **8000**.

- Path-based routes: `/api/v1/policies/**`, `/api/v1/customers/**` → policy;
  `/api/v1/claims/**`, `/api/v1/adjusters/**` → claim; `/api/v1/payments/**` → payment.
- Validation Service is **not** routed; it is internal and event-driven.
- A global filter assigns or propagates `X-Correlation-ID` (ADR-0007); `DedupeResponseHeader`
  keeps a single copy on the response.
- A custom `ErrorWebExceptionHandler` returns the standard `ApiError` JSON: **503** when a
  downstream is unreachable, **504** on timeout (instead of a generic 500).
- Downstream URLs are environment variables, so the same jar works locally and in Docker.
- JWT validation will be added here in Phase 7 (ADR-0013).

The gateway does **not** depend on `common`: that module is Servlet-based
(`OncePerRequestFilter`, `@RestControllerAdvice`) and would pull Spring MVC into a WebFlux app.

## Consequences

- ✅ One entry point and one place for auth, logging and rate limiting later.
- ✅ Internal topology can change without touching clients.
- ⚠️ An extra network hop and one more process to run.
- ⚠️ A single point of failure unless run with more than one instance.
- ⚠️ The correlation header name is duplicated between `common` and the gateway (a tiny, deliberate duplication).

## Alternatives considered

- **No gateway:** clients call services directly; rejected for the reasons above.
- **Nginx / Kong / cloud API gateway:** production-grade, but adds non-Java tooling; Spring Cloud
  Gateway keeps the whole stack in Java and is easy to extend with filters.
- **Spring Cloud Gateway MVC (servlet):** would allow reusing `common`, but the reactive gateway is
  the mainstream, better-documented option and handles many concurrent connections with few threads.
