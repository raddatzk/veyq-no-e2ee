# Architecture Decision Records (ADRs)

Short, numbered records of **stable** architectural or product decisions — the durable trace of *why*
something is the way it is. One file per decision: `NNNN-kebab-title.md`, numbered from `0001`.

- **Status:** `Proposed` · `Accepted` · `Superseded by NNNN`. Supersede rather than delete.
- Keep them concise: context, the decision, consequences — not a tutorial.
- Refinements that produce a durable decision create an ADR; change-requests link the ADRs they touch.
- **Current state, edited in place.** State the decision *as it is now*; when it changes, **edit the ADR** and
  let **git** hold the history — **no** `Amended: <date>` lines or `## Decision — addendum <date>` sections
  (a change-log belongs in git, not the doc). Keep the full current reasoning + rejected alternatives. Canonical
  rule: [`../README.md`](../README.md).

## Format

```
# ADR NNNN — Title
- Status: Accepted
- Date: YYYY-MM-DD

## Context
What forces the decision.

## Decision
What we chose.

## Consequences
What follows — including the costs and the alternatives rejected.
```
