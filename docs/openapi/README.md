# OpenAPI contracts (v1)

The published, versioned API contract of each service, generated from the code and **checked by the
build** ([ADR-0030](../adr/0030-api-versioning-and-openapi-contracts.md)).

| File | Service | Base path | Contract version |
|---|---|---|---|
| [`policy-service.v1.json`](policy-service.v1.json) | Policy API | `/api/v1/customers`, `/api/v1/policies` | 1.0.0 |
| [`claim-service.v1.json`](claim-service.v1.json) | Claim API | `/api/v1/claims`, `/api/v1/adjusters` | 1.0.0 |
| [`payment-service.v1.json`](payment-service.v1.json) | Payment API | `/api/v1/payments` | 1.0.0 |

validation-service has no public API (it only consumes Kafka events).

## Browse them

- **One Swagger UI for everything:** `http://localhost:8000/swagger-ui.html` (the gateway) → pick the
  service in the top-right dropdown. "Try it out" calls go through the gateway.
- Per service, when run with `java -jar`: `http://localhost:808x/swagger-ui/index.html` (group `v1`).
- Raw: `GET /v3/api-docs/v1` on a service, or `GET /api-docs/<service>` on the gateway.
- Paste a file into [editor.swagger.io](https://editor.swagger.io), or generate a client with OpenAPI Generator.

## What every contract contains

- `info.version`: the contract version (semantic), `servers`: the gateway
- only `/api/v1/**` (no actuator)
- correct success codes (`201` + `Location` on creates, `200` replay on FNOL, `ETag` on claim reads/writes)
- error responses with the shared `ApiError` schema: 400 and 500 everywhere, plus 404 / 409 / 412 / 422 where they apply
- the optional `X-Correlation-ID` header on every operation

## Changing the API

1. Change the code.
2. `mvn verify` fails: *"The claim-service API differs from docs/openapi/claim-service.v1.json …"*.
3. Regenerate: `mvn verify -Dopenapi.update=true`.
4. Review the JSON diff in the pull request:
   - **additions** (new endpoint, new optional field, new response field) → bump the minor version (`1.1.0`)
   - **removals, renames, new required fields, type changes** → breaking: needs `/api/v2` alongside v1
