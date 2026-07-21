---
id: cr-drop-cr-numbers
title: Drop the CR number — slug-only cr-<slug>.md naming
status: done
satisfies: []
adrs: []
invariants:
  - All docs in English
---

## Goal

Retire the CR *number*. A change-request is named `cr-<slug>.md` with a descriptive slug and **no number** —
`cr-space-icon.md`, not `cr-0170-space-icon.md`. This dissolves inbox #107 (a burned CR number silently reused,
because `validate_changes.py` only ever saw live files, never history) at the root instead of patching it: with
no number there is nothing to allocate, no max-ever to track, and no history to scan. This CR file is itself the
first slug-named CR, so landing it exercises the new scheme end-to-end.

## Context & constraints

- The problem and the decision: inbox #107, and the measured sparring that chose *dissolution over a monotonic
  helper* — the number's only live job (telling two parallel CRs apart) the slug already does, and its only
  failure mode (silent reuse) came entirely from its durability in immutable history + code comments.
- The durable home for the *why* is [`README.md`](README.md) § naming (this CR is deleted when done; the rule
  must not die with it).
- The `cr-` prefix is kept as a pure namespace marker — it is the allowlist that separates a CR from this dir's
  `README.md` and any `review-*.md` finding backlog (`validate_changes.py` globs `cr-*.md`).
- Trust-chain: `scripts/validate_changes.py` is a guard; `scripts/validate_guard_gaps.py` forges a CR fixture,
  so both move together. Guard changes must leave `for f in scripts/validate_*.py; do python3 "$f"; done` green.

## Phases

Single phase (tooling + docs; no contracts → backend → web).

- [x] `scripts/validate_changes.py` — filename check `cr-<slug>.md` (lowercase slug), `id` == filename stem,
      uniqueness dropped (structural: the filesystem enforces distinct filenames). · Done: guard green.
- [x] Rename the one live CR `cr-0170-space-icon.md → cr-space-icon.md` + `id: cr-space-icon`.
- [x] `changes/README.md` — naming rule (`cr-<slug>.md`, no number) + harvest the #107 reasoning; template
      `id: cr-<slug>`.
- [x] `scripts/validate_guard_gaps.py` — slug the forged CR fixture; correct the deferred-gap docstring that
      named the now-unbuilt #107 monotonic helper.
- [x] `.claude/skills/trust-chain/SKILL.md` — correct the parallel "burned CR number → #107 helper" claim.
- [x] `docs/inbox.md` — mark #107 dissolved; normalize the four forward-refs to not-yet-allocated numbers
      (capability-discovery / anonymous-acceptance-gate / set-location / live-updates CRs).
- [x] Bite-test the guard, then run the full trust-chain glob green.

## Definition of done

`for f in scripts/validate_*.py; do python3 "$f"; done` all green, and the new `validate_changes.py` **bites**:
a CR whose `id` disagrees with its filename stem, or a filename that isn't a lowercase slug, must turn it red
(proven by snapshot-and-restore, per README § bite-proof). No coverage YAML changes (this CR `satisfies:`
nothing — it is tooling/convention, like cr-0180).

## Retrospective

- **Planning miss (mine):** the first plan took inbox #107's stated remedy at face value and designed the
  *monotonic next-number helper* — machinery to make the number safe. The better fix was to ask whether the
  number needs to exist at all; it was the user's sparring question that dissolved it. `changes/README.md`'s own
  "the CR carries the locator, not the number" doctrine already pointed there — I should have surfaced *that* as
  the lead, not a counter. **Root cause:** treated a guard-gap as "build a guard/helper" instead of first asking
  whether the guarded artifact is load-bearing. **Next time:** when an inbox item's remedy is "build a
  helper/guard," spend one line asking whether the thing it guards should exist — the cheapest fix is often
  deletion, not tooling.
- **Execution miss (small, caught pre-build):** the option I offered the user (bare `<slug>.md`) missed that
  `changes/` also holds `README.md` and `review-*.md`, so the `cr-*.md` glob needs the `cr-` prefix as an
  allowlist. Caught while writing the guard, flagged to the user, deviated deliberately — but the cleaner move
  is to check a directory's *sibling files* before proposing a filename scheme for it.
- **Process (went well):** grounded before building, bite-tested the guard before trusting it (the id-mismatch
  and bad-filename cases both went red first), and waited for the explicit "los" before implementing — the
  agreed memory conventions held. German throughout the conversation, English in the artifacts.
- **Next time:** one line — *does the guarded thing need to exist?* — before planning any guard/helper.
