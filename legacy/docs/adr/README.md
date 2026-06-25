# Architecture Decision Records

Use this directory for decisions that should remain traceable after implementation. ADRs are for stable product and architecture decisions, not for loose ideas or backlog notes.

Use product documents such as [docs/product/future-work.md](../product/future-work.md) for exploratory topics until the team is ready to make a decision.

## When to Write an ADR

Write an ADR when a decision changes or fixes a durable rule for the project, especially:

- Product terminology, such as the migration from albums to memories.
- Privacy boundaries and public sharing behavior.
- Permission models and upload ownership.
- Storage, media processing or filesystem layout.
- Licensing, trademark and commercial/community split.
- Synchronization and conflict semantics.
- Schema, API or data migrations.
- Optional central services such as push gateways, managed hosting or federation.

Do not write an ADR for small implementation details that are obvious from local code, temporary TODOs or ideas that still belong in product exploration docs.

## Naming

Use sequential files:

- `0001-short-decision-title.md`
- `0002-next-decision.md`

## Template

```markdown
# ADR 0000: Decision Title

## Status

Proposed | Accepted | Superseded

## Context

What problem are we deciding on, and what constraints matter?

## Decision

What are we choosing?

## Consequences

What gets easier, harder or intentionally deferred?
```

## Candidate ADRs

The first likely ADRs should come from [docs/product/future-work.md](../product/future-work.md), especially Memory terminology, Memory tree invariants, public sharing privacy, asset immovability and licensing.
