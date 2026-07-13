---
id: cr-0159
title: Coverage model v2 — gaps mean uncovered surfaces (overlap forbidden) + demote http from a coverage surface (audit P1b, ADR 0040)
status: done
satisfies: []
adrs: ["0040"]
invariants: []
---

## Goal

Implement [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md): finish audit finding P1b
(inbox #62) after cr-0158's class-scoped-resolution core, merging the two split sub-findings (#81 gaps-overlap,
#82 CI-vs-live) into one coverage-model change. Two things: (1) `gaps` gets exactly one meaning — "a surface with
no coverage ref for this use-case" — so an area appearing in both `coverage` and `gaps` becomes a hard validation
error (the enforceable stale-gap guard #81 wanted); the build-notes and deferred-sub-slice prose that had been
stuffed into `gaps` move out. (2) `http` stops being a coverage surface: the `.http` files stay in the repo as
IntelliJ dev tooling but no longer count in the trust chain, which dissolves #82 without any CI-gating marker
(the residual non-CI-proven criteria are native-UITest-only = the known #80 native gap).

## Context & constraints

- Realises **ADR 0040** (`adrs: ["0040"]`). Tooling/guardrail + a data migration + one small backend test — **no
  use-case satisfied** (`satisfies:` empty), not a contract/crypto change, **no** operator-blind surface touched.
- The validator is a CI trust-chain guard (`changes/README.md`) — it must stay deterministic and **green at each
  phase boundary**. The phase order below keeps the tree working throughout: the one criterion that would be left
  ref-less by the http strip gets its backend test **first**.
- Measured against the current corpus (drives the scope): `http` is the **sole** coverage area for exactly **1 of
  256** criteria (`uc.account.login/boundary-validation`); **67** criteria reference `http` (the other 66 pair it
  with a backend SliceTest proving the same behaviour in CI); **16** files list an area in both `coverage` and
  `gaps`. `.http` runs in **no** CI job (confirmed: no `ijhttp`/`httpyac` in any workflow).
- `backend/src/test/.../account/login/LoginSliceTest.kt` already has `refreshWithNeitherCookieNorBodyTokenIs400`
  (covers "missing refresh token → 400"); the criterion's other boundary cases (malformed email, non-base64
  signature → 400) need one added test.

## Phases

- [x] 1 backend — add the `LoginSliceTest` boundary test (malformed email / non-base64 signature → 400 via
      `@Valid`; missing-refresh-token → 400 stays covered by the existing `refreshWithNeitherCookieNorBodyTokenIs400`)
      so `uc.account.login/boundary-validation` stays CI-covered once its `.http` ref is dropped · Done when:
      `./gradlew test` (in `backend/`) green with the new test.
- [x] 2 coverage — strip every `http:` block from the coverage YAMLs (repoint `login/boundary-validation` to the
      new backend ref; the other 66 already carry a backend/web ref); migrate the 16 overlaps — relocate the
      build-note and deferred-sub-slice prose out of `gaps` (drop it, or as an inbox/future-CR note), leaving
      `gaps` only for genuinely-uncovered surfaces; keep every `status: planned` file with ≥1 real gap · Done
      when: `python3 scripts/validate_coverage.py` green (still with `http` in the area set, now unused) and no
      criterion is ref-less.
- [x] 3 tooling+docs — `scripts/validate_coverage.py`: drop `http` from `KNOWN_AREAS` + the `http` branch of
      `check_ref`; add the **overlap-forbidden** rule (an area under `gaps` must not appear in any criterion's
      `coverage`). Rewrite `docs/coverage/README.md` (area set without `http`; the new one-meaning `gaps`
      semantics + the overlap rule) and `docs/ai/README.md` (drop `http` from the "verification surfaces"
      framing; phase order → `contracts → backend → web → apple → android`) · Done when: all three validators
      green, the overlap-injection check **fails** as expected (then reverted), and `validate_doc_links.py` +
      `validate_changes.py` green.

## Out of scope

- **No CI-gating marker / `ci-surfaces.yaml` / `ci: false`** — ADR 0040 rejects it; #82 is resolved by the http
  demotion, not by new schema.
- **The #80 native CI job** — the residual native-UITest-only criteria stay honestly visible as the #80 gap;
  making them CI-proven is #80's work, not this CR.
- **Deleting the `.http` files** — they remain as dev tooling / living request examples; only their coverage
  *claim* goes away. (A future `ijhttp` CI job that would make them CI-proven is explicitly deferred per ADR
  0040 — the backend SliceTests already cover the behaviour in CI.)
- **The class-scoped resolution + `/build/` exclusion** — done in cr-0158.

## Verify

- `python3 scripts/validate_coverage.py` **green** after each phase (no criterion ref-less; no `coverage`/`gaps`
  overlap remains; no `http` refs remain).
- **Overlap-injection (manual, matching cr-0158's drift check):** temporarily re-add an area to a file's `gaps`
  that a criterion already covers → the validator **fails** ("area in both coverage and gaps"); revert.
- **http-removal completeness:** `grep -rn "http:" docs/coverage/features` returns nothing (no stray http block);
  a coverage file that still names `http` as an area fails with "unknown area 'http'".
- `./gradlew test` (in `backend/`) green — the new `LoginSliceTest` boundary test(s) pass.
- `python3 scripts/validate_doc_links.py` + `python3 scripts/validate_changes.py` green (ADR 0040 link +
  this CR's frontmatter resolve).

## Definition of done

`scripts/validate_coverage.py` forbids a `coverage`/`gaps` overlap (stale gaps now fail) and no longer knows the
`http` area; every coverage YAML is http-free with the 16 overlaps migrated and every criterion still covered by
a real (CI-relevant) ref or an explicit gap; `LoginSliceTest` covers `login/boundary-validation` at the backend;
`docs/coverage/README.md` + `docs/ai/README.md` reflect the http demotion + the one-meaning `gaps`. All three
trust-chain validators + `./gradlew test` (in `backend/`) green; the overlap-injection check fails as expected. Inbox #81/#82
already record the refine → ADR 0040 (done in the refine); mark them **done → cr-0159** on completion.

## Retrospective

- **The migration was bigger mechanically than the finding implied, so it paid to script the mechanical half
  and hand-do the judgement half.** Two comment-preserving line-based strippers (one for `http:` blocks, one for
  the overlapping-area gap entries) did 29 + 15 files deterministically; the per-file judgement (which gap prose
  was a build-note to drop vs a deferred slice, orphaned trailing comments, empty-`gaps:` cleanup) stayed manual.
  A YAML round-trip would have destroyed the rich header/inline comments — line-based editing was the right call.
- **`git checkout --` bit again — this time worse than in cr-0158.** For the overlap-injection verify I reverted
  the test file with `git checkout -- browse.yaml`, forgetting the file had **uncommitted migration edits** — so
  the checkout clobbered them back to the pre-migration committed state. Caught it immediately (the final
  green-check went red), and the idempotent scripts made recovery a one-liner. Lesson, now twice over: to
  save/restore a file that has uncommitted changes, copy it aside (or stash), never `git checkout --`.
- **A CR claim didn't survive contact with the code: bean-validation 400s carry no `{code}`.** The plan said the
  boundary test would assert `code=invalid_request`, but only the *handler*-thrown 400 (missing refresh token)
  sets that; a `@Valid` shape violation returns the container's 400 with no veyq `{code}` — which is exactly why
  the old `.http` asserted only the status. Reading the actual `.http` assertions before writing the test caught
  it; the test asserts `400` only, and the CR phase text was corrected. Cheap-diagnostic-first (read the existing
  assertion, not guess the code) held up.
- **The http demotion rippled into prose, not just data.** Beyond the coverage refs, several files carried
  comments boasting "X is now on the http surface" and a governance line in `changes/README.md` ("+ any http/
  acceptance") — all now false. Scrubbing the active coverage-claims (while leaving accurate descriptions of what
  the still-existing `.http` dev files do) kept the durable docs honest per the "state as it is now" rule. Worth
  remembering: demoting a surface is a docs sweep, not only a schema edit.
