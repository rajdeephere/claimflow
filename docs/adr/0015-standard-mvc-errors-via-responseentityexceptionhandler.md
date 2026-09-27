# ADR-0015: Handle standard Spring MVC errors via ResponseEntityExceptionHandler

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 2

## Context

`GlobalExceptionHandler` started as a plain `@RestControllerAdvice` with one handler per exception
plus a catch-all `Exception` handler. Twice (BUG-001 in Phase 1, BUG-006 in Phase 2) a standard
client error (unknown route, malformed UUID, bad enum, missing parameter) fell into the catch-all
and came back as **500**. Adding handlers one by one is whack-a-mole: Spring MVC has ~15 such exceptions.

## Decision

`GlobalExceptionHandler` **extends `ResponseEntityExceptionHandler`**. Spring already maps every
standard MVC exception to the correct status (400, 404, 405, 406, 415, 503, ...). We only override:

- `handleExceptionInternal`: render our `ApiError` body instead of `ProblemDetail`
- `handleMethodArgumentNotValid`: include field violations
- `handleTypeMismatch`: a clear message, listing allowed enum values

Domain exceptions (`ResourceNotFoundException`, `ConflictException`, `BusinessRuleException`) and the
catch-all stay as explicit `@ExceptionHandler`s.

## Consequences

- ✅ Correct status codes for all standard MVC errors, including ones not yet encountered (verified with 405 and 415 tests).
- ✅ Future Spring versions that add exceptions are covered automatically.
- ⚠️ Slightly more framework knowledge is needed to read the handler.

## Alternatives considered

- **Keep adding individual handlers:** rejected; it caused two bugs already.
- **Return RFC 7807 `ProblemDetail` as-is** (`spring.mvc.problemdetails.enabled=true`): a good
  standard, but the gateway and all services already share the `ApiError` shape with `correlationId`
  and `violations`; switching formats later is possible and would be its own ADR.
