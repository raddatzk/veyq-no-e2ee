# Patterns & Conventions

> Design patterns and rules that are **binding across the codebase** — i.e. conventions every
> contributor must follow. Local, self-evident pattern usage (a single Observer in one view) is **not**
> documented here; the code is the source of truth for that. Document a pattern here only when it is a
> rule, not an implementation detail.

> Code-level conventions (layering, naming, comments, validation, error handling, config, testing) live in
> the [Developer Styleguide](developer-styleguide.md); project rules (invariants, commits, terminology) in
> [`docs/ai/working-methodology.md`](../../ai/working-methodology.md). This page is for *architectural* patterns.

## Rules (seed — fill as they are decided)

- *(placeholder)* Cross-context communication: how contexts talk (direct call vs. domain events /
  publish-subscribe) — to be decided during the Bounded-Contexts step and recorded here + as an ADR.
- *(placeholder)* Error handling & stable error codes (client localizes) — see
  [Platform Concepts](platform-concepts.md).

## When to add an entry

Use the litmus test: *would a competent developer be surprised, or get it wrong, without this written
down — and is it a rule everyone must follow?* If yes, add it here (and an [ADR](../decisions/README.md)
if it is a stable decision). If it is just how one class happens to work, leave it to the code.
