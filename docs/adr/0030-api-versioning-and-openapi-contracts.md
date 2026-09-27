# ADR-0030: API versioning and versioned OpenAPI contracts

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** post-8 (API documentation)

## Context

Clients (a claims portal, partner systems, the Postman collection, generated SDKs) depend on the exact
shape of the API. Until now the Swagger docs were springdoc defaults: no version, actuator endpoints
mixed in, `201` endpoints documented as `200`, no error responses, and nothing stopped an accidental
breaking change (renaming a field) from shipping.

## Decision

### Versioning scheme
- **URI major version:** `/api/v1/...` (already in place since Phase 1). A breaking change gets
  `/api/v2/...` running *alongside* v1 until clients migrate.
- **Contract version** in the OpenAPI `info.version`, semantic: **`1.0.0`** today.
  - **Minor** (1.1.0): backwards-compatible additions: new endpoint, new optional request field, new response field, new enum value *documented as open*.
  - **Patch** (1.0.1): documentation-only fixes.
  - **Major** means a new URI version (`/api/v2`): removing or renaming a field or endpoint, making a
    field required, changing a type or format, changing status-code semantics.
- **Deprecation:** mark operations `deprecated: true` in OpenAPI and return `Deprecation` / `Sunset`
  headers for at least one release before removing v1 endpoints.

### One definition style for every service (`common/.../openapi/OpenApiConfig`)
- Group **`v1`** = `/api/v1/**` only, published at **`/v3/api-docs/v1`** (actuator and internals excluded).
- `info`: title and description per service (`claimflow.api.*`), `version` = contract version.
- `servers`: the **API gateway**, the only public entry point, so "Try it out" goes through routing and the correlation filter.
- Shared components: the **`ApiError`** schema (+ `FieldViolation`), the optional **`X-Correlation-ID`**
  header on every operation, **400** and **500** on every operation.
- Endpoint-specific statuses declared with **`@ApiErrors({404, 409, 412, 422})`**; success codes that
  springdoc can't infer are explicit (`201` + `Location` on creates, `200` replay on FNOL, `ETag` on claim reads/writes).
- validation-service has no public API: its OpenAPI and Swagger UI are disabled.

### Contracts committed and checked
- `docs/openapi/<service>.v1.json` (canonical: sorted keys, LF line endings) is **the published contract**.
- An integration test per service (`publishedOpenApiContractIsUpToDate`) compares the live
  `/v3/api-docs/v1` with the committed file and **fails the build on any difference**.
- Intended changes: `mvn verify -Dopenapi.update=true`, then review the JSON diff in the pull request;
  that's where "is this breaking?" is decided against the rules above.

### One UI
The gateway serves **`/swagger-ui.html`** with a dropdown per service; it proxies each service's
`/v3/api-docs/v1` at `/api-docs/<service>`.

## Consequences

- ✅ API changes are visible and reviewable; an accidental rename fails CI instead of breaking clients.
- ✅ The docs are correct enough to generate clients from (status codes, error schema, headers).
- ✅ One entry point for humans (gateway Swagger UI) and machines (the committed JSON).
- ⚠️ Every intended API change needs a contract regeneration in the same commit (by design).
- ⚠️ The diff shows *that* something changed, not *whether it's breaking*; a tool like `openapi-diff` or
  `oasdiff` in CI could classify changes automatically (future work).

## Alternatives considered

- **Header / media-type versioning** (`Accept: application/vnd.claimflow.v2+json`): cleaner URIs, but
  harder to use from browsers, curl, gateways and caches; URI versioning is the most common for public REST APIs.
- **Contract-first** (write OpenAPI YAML, generate controllers): strongest guarantee, but a big workflow
  change mid-project. Code-first plus committed, checked contracts gives most of the benefit.
- **Consumer-driven contract tests (Pact):** verifies what each consumer actually uses; valuable once
  there are independent consumer teams.
