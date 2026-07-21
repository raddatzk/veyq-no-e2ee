---
id: cr-adr-citation-guard
title: ADR 0017 honesty + a guard that every cited ADR resolves
status: done
satisfies: []
adrs: ["0017"]
invariants:
  - Operator-blind E2EE — the backend performs no content crypto
  - All docs in English
---

## Goal

Close inbox #95(a). Two moves: (a1) correct ADR 0017, which claimed *"the operator-blind backend verifies
signatures against the same file"* (`contracts/crypto/vectors/v1.json`) — measured false: the backend reads that
file nowhere, performs no content crypto, and the Ed25519 login-auth signatures it *does* verify go against the
stored `authPublicKey`, not the vectors. Since the native drop (`7a1c4e9f`), the web conformance test is the
only asserting stack, so the file is a single-implementation regression pin, not a cross-stack parity seam.
(a2) add the guard `docs/decisions/README.md` itself asks for — *"no validator checks that [a cited ADR still
resolves]"* — so the next deleted ADR can't leave 27 dangling citations the way ADR 0009 did (swept only later,
cr-0178).

## Context & constraints

- The finding + measurements: inbox #95 (grounded 2026-07-21 — backend `grep` shows only the unrelated
  `tasks/tags/v1.json`; `LoginHandler.kt:3,21` verifies Ed25519 against `authPublicKey`; `backend/CLAUDE.md:24`
  = "no content crypto").
- The gap the guard closes is stated in [`../docs/decisions/README.md`](../docs/decisions/README.md) (lines
  8, 11–12) — this CR flips both to "closed".
- **Guard scope is the design decision** (settled with the user): scan the surfaces where an `ADR NNNN` is
  always a live citation (`contracts/`, `backend/src`, `web/src`, `docs/features`, `docs/crosscutting`, ADR
  bodies); exclude the narrative registers where a *historical* mention of a retired ADR legitimately dangles
  (`docs/inbox.md`, `changes/`, `docs/decisions/README.md`). That exclusion is why no retired-ADR allowlist is
  needed. Rationale lives in the guard's own docstring, not restated here.
- Trust-chain: the new `validate_adr_citations.py` joins the glob for free (glob-is-registry).

## Phases

Single phase (docs + tooling; no contracts → backend → web).

- [x] `docs/decisions/0017-*.md` — rule 4 + the Consequences "seam" bullet: drop the false backend-verifies
      clause; state the single-stack regression-pin reality.
- [x] `scripts/validate_adr_citations.py` — the scoped guard.
- [x] `docs/decisions/README.md` — flip the two "no validator / still open" lines to "closed by the guard".
- [x] `docs/inbox.md` — mark #95(a) + the guard done.
- [x] Bite-test, then the full trust-chain glob green.

## Definition of done

`for f in scripts/validate_*.py; do python3 "$f"; done` all green (now 13 guards), and the new guard **bites**:
a citation to a non-existent ADR in a scanned surface turns it red, while a historical mention in an excluded
surface (the inbox's ADR 0009 narrative) stays green — both proven. No coverage YAML changes (tooling, no
`satisfies:`).

## Retrospective

- **Planning (went well):** the earlier agent grounding gave the exact rule-4 text and the backend `grep`
  up front, so a1 was a precise two-line correction, not a hunt. The one judgment that needed care — a guard
  that doesn't false-positive on historical ADR mentions — was surfaced *before* building and settled by
  scoping, not by a fragile allowlist.
- **Execution (one honest limit, by design):** the guard does not watch `docs/inbox.md`, so a dangling ADR
  ref in the inbox is not caught. Chosen deliberately (zero false positives on legitimate history) and stated
  in the docstring + the DoD, per "no silent caps".
- **Next time:** when a doc claims a cross-component invariant ("X verifies against Y"), the one-line `grep`
  that Y is actually read goes in at refine — this ADR's clause survived a native-surface drop unchecked
  because nothing ever asserted it.
