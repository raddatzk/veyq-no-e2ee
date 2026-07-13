---
id: cr-0159
title: Coverage model v2 — gaps mean uncovered surfaces (overlap forbidden) + demote http from a coverage surface (audit P1b, ADR 0040)
status: planned
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

- [ ] 1 backend — add the `LoginSliceTest` boundary test(s) (malformed email / non-base64 signature / missing
      token → 400, `code=invalid_request`) so `uc.account.login/boundary-validation` stays CI-covered once its
      `.http` ref is dropped · Done when: `./gradlew :app:test` (backend) green with the new test(s).
- [ ] 2 coverage — strip every `http:` block from the coverage YAMLs (repoint `login/boundary-validation` to the
      new backend ref; the other 66 already carry a backend/web ref); migrate the 16 overlaps — relocate the
      build-note and deferred-sub-slice prose out of `gaps` (drop it, or as an inbox/future-CR note), leaving
      `gaps` only for genuinely-uncovered surfaces; keep every `status: planned` file with ≥1 real gap · Done
      when: `python3 scripts/validate_coverage.py` green (still with `http` in the area set, now unused) and no
      criterion is ref-less.
- [ ] 3 tooling+docs — `scripts/validate_coverage.py`: drop `http` from `KNOWN_AREAS` + the `http` branch of
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
- `./gradlew :app:test` (backend) green — the new `LoginSliceTest` boundary test(s) pass.
- `python3 scripts/validate_doc_links.py` + `python3 scripts/validate_changes.py` green (ADR 0040 link +
  this CR's frontmatter resolve).

## Definition of done

`scripts/validate_coverage.py` forbids a `coverage`/`gaps` overlap (stale gaps now fail) and no longer knows the
`http` area; every coverage YAML is http-free with the 16 overlaps migrated and every criterion still covered by
a real (CI-relevant) ref or an explicit gap; `LoginSliceTest` covers `login/boundary-validation` at the backend;
`docs/coverage/README.md` + `docs/ai/README.md` reflect the http demotion + the one-meaning `gaps`. All three
trust-chain validators + `./gradlew :app:test` green; the overlap-injection check fails as expected. Inbox #81/#82
already record the refine → ADR 0040 (done in the refine); mark them **done → cr-0159** on completion.

## Retrospective

_Filled on completion._
