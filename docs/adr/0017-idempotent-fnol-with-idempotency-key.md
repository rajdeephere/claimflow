# ADR-0017: Idempotent FNOL with an Idempotency-Key header

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 3

## Context

`POST /claims` isn't naturally idempotent. A mobile app or partner system that times out and retries
would create a **duplicate claim**, which could then be paid twice. Retries are normal on unreliable
networks, and the client can't tell "request lost" from "response lost".

## Decision

Clients may send an `Idempotency-Key` header (e.g. a UUID generated once per loss report).

- It's stored in `claims.idempotency_key` with a **UNIQUE** constraint.
- If the key already exists, the original claim is returned with **200 OK** (a new claim returns **201 Created**).
- **Concurrent retries:** the loser's insert violates the unique constraint. Because that marks its
  transaction rollback-only, `fileFnol` isn't `@Transactional`. Each step runs in its own
  transaction via `TransactionTemplate`, and the loser re-reads the winner's claim in a fresh
  transaction.
- The header is optional (max 100 characters). Without it, behaviour is a plain create.

## Consequences

- ✅ Safe client retries; verified with 8 concurrent requests → 1 claim, 1 × 201, 7 × 200.
- ✅ The DB constraint is the guarantee; there's no in-memory lock, so it works across multiple instances.
- ⚠️ A reused key with a *different* body returns the original claim silently. Production APIs
  (e.g. Stripe) store a request hash and return 422 on mismatch, a possible enhancement.
- ⚠️ Keys are kept forever here; a real system would expire them after a retention window.

## Alternatives considered

- **Natural-key dedupe** (same policy + date + amount): wrongly merges genuinely separate losses.
- **Client-generated claim ID with PUT:** also idempotent, but moves ID generation to clients.
- **Distributed lock (Redis):** extra infrastructure; the unique constraint is simpler and exact.
