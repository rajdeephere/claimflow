# ADR-0013: JWT authentication at the gateway, roles in services

- **Status:** Proposed
- **Date:** 2026-09-27
- **Phase:** 7

## Context

Endpoints need authentication and role-based authorization (CUSTOMER, ADJUSTER, CLAIMS_MANAGER, ADMIN).

## Decision

The gateway validates JWTs (signature, expiry) and rejects unauthenticated requests with 401.
Services are configured as OAuth2 resource servers too (defence in depth) and enforce roles with
`@PreAuthorize`, returning 403 when a role is missing. Tokens are issued by a simple dev issuer
for local use; production would use an identity provider (Keycloak, Azure AD, Okta).

## Consequences

- ✅ Stateless auth that scales horizontally.
- ⚠️ Tokens can't be revoked before expiry without extra machinery; keep lifetimes short.

## Alternatives considered

- **Session cookies:** stateful; awkward across services.
- **Auth only at the gateway:** simpler, but a service reached directly would be unprotected.
