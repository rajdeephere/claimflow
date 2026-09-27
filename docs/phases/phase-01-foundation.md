# Phase 1 — Foundation

**Status:** ✅ Complete

## Goal

Set up the repository, build and local infrastructure so each later phase only adds business code.

## Delivered

| Item | Location |
|---|---|
| Maven multi-module build (Java 17, Spring Boot 3.3.5) | `pom.xml` |
| Shared library: correlation ID filter, `ApiError`, exception hierarchy, `GlobalExceptionHandler` | `common/` |
| Service skeletons with actuator health and OpenAPI/Swagger UI | `policy-service/`, `claim-service/`, `validation-service/`, `payment-service/` |
| PostgreSQL 16 with one database per service | `docker-compose.yml`, `infra/postgres/init-databases.sql` |
| Kafka 3.8 (KRaft) and Kafka UI | `docker-compose.yml` |
| API gateway (Spring Cloud Gateway): routes, correlation ID filter, 503/504 error handler | `api-gateway/` |
| System design doc and ADRs 0001–0013 | `docs/system-design.md`, `docs/adr/` |
| Flyway wired into the DB-backed services (`ddl-auto: validate`) | `*/src/main/resources/application.yml` |

## Exception → HTTP mapping

| Exception | Status |
|---|---|
| `MethodArgumentNotValidException`, `HttpMessageNotReadableException` | 400 |
| `ResourceNotFoundException`, `NoResourceFoundException` | 404 |
| `ConflictException` | 409 |
| `BusinessRuleException` | 422 |
| anything else | 500 (logged, message hidden from client) |

## Verification

| Check | Result |
|---|---|
| `mvn package` builds all 6 modules | ✅ |
| `docker compose up -d --wait` → postgres, kafka healthy; `policy_db`, `claim_db`, `payment_db` exist | ✅ |
| policy-service starts, Flyway connects, `GET /actuator/health` → `{"status":"UP"}` | ✅ |
| `X-Correlation-ID: demo-123` is echoed back and appears in log lines | ✅ |
| Unknown route returns 404 `ApiError` | ✅ (after fix below) |
| Swagger UI at `/swagger-ui/index.html` | ✅ |
| Gateway routes `/api/v1/policies/**` to policy-service; correlation ID reaches the service (`correlationId` in its error body) | ✅ |
| Gateway generates an ID when the client sends none | ✅ |
| Downstream down → gateway returns 503 `ApiError` | ✅ (after fix below) |
| Exactly one `X-Correlation-ID` response header | ✅ (after fix below) |

## Issues found & fixed

1. **Unknown route returned 500.** Spring 6 raises `NoResourceFoundException` for unmapped paths,
   which the catch-all `Exception` handler turned into a 500. Added a dedicated 404 handler.
2. **Postgres auth failure on 5432.** A local PostgreSQL install already listens on 5432.
   The container is mapped to host port **5433** instead.
3. **Gateway port 8080 in use.** A local Apache httpd listens on 8080; the gateway runs on **8000**.
4. **Duplicate `X-Correlation-ID` response header.** Both the gateway and the downstream service set it.
   Fixed with the `DedupeResponseHeader=X-Correlation-ID, RETAIN_FIRST` default filter.
5. **Service down returned 500 from the gateway.** Added `GatewayErrorHandler`
   (`ErrorWebExceptionHandler`, ordered before Spring Boot's default) that maps `ConnectException`
   to 503 and `TimeoutException` to 504 in the standard error format.

## Deferred

- Optimistic-lock → 409 mapping (needs Spring ORM; added in Phase 7).
- Service containers in Compose (added with Dockerfiles in Phase 8).
