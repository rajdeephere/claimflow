# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 1

## Context

Design choices in a distributed system are easy to forget and hard to reverse. New team members
(and reviewers) need to know *why* something was built a certain way, not just *what* was built.

## Decision

Record every significant decision as a short Architecture Decision Record (ADR) in `docs/adr/`,
numbered sequentially, using the template in [`template.md`](template.md). ADRs are immutable once
accepted; a changed decision gets a new ADR that **supersedes** the old one.

## Consequences

- ✅ Decisions and their trade-offs are reviewable in pull requests alongside code.
- ✅ Superseded ADRs keep the history of how the design evolved.
- ⚠️ Small overhead per decision.

## Alternatives considered

- Wiki pages: drift away from code, not versioned with it.
- Comments in code: too local for cross-cutting decisions.
