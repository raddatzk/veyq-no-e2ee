# ADR 0040 — Coverage model: `gaps` are uncovered surfaces; `http` is IDE tooling, not a coverage surface

- Status: Accepted
- Date: 2026-07-13

> Realises the rest of audit finding **#62 (P1b)** after [cr-0158](../../changes/cr-0158-coverage-validator-class-resolution.md)
> did the class-scoped-resolution core. Merges the two split sub-findings — inbox **#81** (coverage/`gaps`
> overlap) and **#82** (CI-vs-live levels) — because the refine found they share one root cause. To be
> implemented by a change-request (P1b follow-up); this ADR is the decision it builds on.

## Context

The acceptance-coverage trust chain (`feature → use-case → criteria → tests` — see
[docs/coverage/README.md](../coverage/README.md)) records, per acceptance criterion, a `coverage` map keyed by
**area** (`backend · http · web · apple · android`) then **level** (`unit · integration · e2e`), plus a
per-use-case `gaps` map (`area: reason`) for surfaces "intentionally not (yet) covered". `scripts/validate_coverage.py`
enforces that every criterion has ≥1 test and every ref resolves.

P1b flagged three things. The class-vs-flat resolution is done (cr-0158). The remaining two were split to #81
(an area appearing in both `coverage` and `gaps` — 16 files) and #82 (`level: e2e` conflating CI-run tests,
IDE-only `.http`, env-gated live smokes, and native UITests with no CI job). Refining them **together** surfaced
a single root cause: **`gaps` had drifted into an overloaded free-form per-area status field**, doing three
different jobs at once —

1. a **real gap** — the surface is not built (`apple`/`android` "Not built");
2. a **deferred sub-slice** — the core is covered, a later feature is pending (`uc.memory.date` "the aggregated
   cross-chapter timeline is a later CR"; `uc.memory.place` "native map parity; blind gazetteer");
3. a **build-note for a manual/live smoke** — the CI tests exist, the end-to-end check was a human clicking once
   (`uc.memory.gallery` "the live browser smoke … driven manually per the CR done-gate").

The 16 overlaps are the *symptom* of (2)+(3) sitting in `gaps`. The naive "forbid overlap" the finding first
proposed is a misdiagnosis — it would break legitimate partial coverage.

Two facts from the corpus + the real CI config decide the shape:

| Fact | Measured |
|---|---|
| CI actually runs | backend `gradlew check` (unit+integration), web `npm run check` + `run_web_e2e.py` (unit+e2e), the trust-chain validators |
| CI does **not** run | `.http` (no `ijhttp`/`httpyac` in any workflow), `apple`/`android` (no job — #80), env-gated smokes (`LiveRoundTrip*`, `BlobBatchE2E*`) |
| `http` as **sole** coverage area | **1 of 256** criteria (`uc.account.login/boundary-validation`) — the other 66 pair it with a backend SliceTest that proves the same behaviour in CI |
| Criteria with **no** CI-proven ref, post-#80 (native unit/integration counted as CI) | **5**: 4 native-UITest-only + the 1 http-only |

So "CI-proven" is not a function of area alone (web e2e runs, `http` e2e never does, an env-gated backend smoke
in an otherwise-CI area does not) — but the part of that which actually bites is tiny: essentially **`http`**.

## Decision

**Two changes, no new schema. #82 is resolved by removing a surface, not by adding a marker.**

1. **`gaps` means exactly one thing:** *this surface has no coverage ref for this use-case (yet), and here is
   why that is acceptable.* Enforced: an area listed under `gaps` **must not** appear in any criterion's
   `coverage` for that use-case — overlap is now a hard validation error, which is the enforceable stale-gap
   guard #81 wanted. The two illegitimate uses leave `gaps`: **build-notes** (the surface is covered — the prose
   belongs to the CR record, not the coverage file) and **deferred sub-slices** (future scope, not untested
   current scope — they belong in the inbox / a future CR).

2. **`http` stops being a coverage surface.** The `.http` files stay in the repo as what they were built for —
   IntelliJ requests a developer fires by hand — but they no longer count in the trust chain. `http` is removed
   from the area set and stripped from the coverage files; the one http-only criterion
   (`uc.account.login/boundary-validation`) gains a backend boundary test (malformed email / non-base64
   signature / missing token → 400) so it stays CI-covered. `docs/ai/README.md`'s "`http/` is a real
   contract-level verification surface" stance and the `contracts → backend → http → web → …` phase order are
   rewritten to drop `http` from the verified surfaces.

3. **No CI-gating marker (#82 needs none).** After (2), the only criteria not CI-proven are native-UITest-only
   ones — and that is precisely the **#80** native-CI gap, honestly visible as a native gap, not a coverage-model
   hole. When #80 adds the native unit/integration CI job, they become CI-proven with no data change. A
   `ci-surfaces.yaml` manifest / a per-ref `ci: false` flag / a filename convention would all be machinery to
   police ~1 http + ~4 native criteria — not worth it.

## Consequences

- **#81 gets an enforceable guard for free.** Overlap-forbidden catches a gap left stale after a surface is
  covered, with zero schema growth. The 16 overlaps are migrated as part of the follow-up CR (build-note and
  deferred prose relocated), leaving `gaps` meaning only "genuinely uncovered".
- **#82 dissolves without machinery** — the "Start light / pain before tool" trade: removing the one decorative
  non-CI surface beats a marker system for the handful of cases left. The merge of #81+#82 into one decision
  reflects that they were one problem (an overloaded `gaps`) seen from two angles.
- **Cost, paid honestly:** this reverses a *documented* stance (`http` as a verification surface); ~67 criteria
  drop their now-redundant `http` refs; one backend boundary test is added; `docs/ai/README.md` +
  `docs/coverage/README.md` are rewritten; the phase order becomes `contracts → backend → web → apple → android`.
- **The `.http` files are not deleted.** They keep their value as dev tooling and living request examples with
  response-handler assertions; they simply make no coverage *claim*. The door stays open: if they ever must be
  CI-proven, the path is an `ijhttp` CI job — rejected now because the backend SliceTests already prove the same
  behaviour in CI, so it would be infrastructure for coverage we already have.
- **Rejected alternatives:**
  - *A CI-gating marker* (`ci-surfaces.yaml` / per-ref `ci: false` / filename convention) — machinery for a
    problem that removing `http` shrinks to the #80 native gap.
  - *Per-criterion `gaps`* — makes covered+gap an exact contradiction, but most gaps are genuinely use-case-wide
    ("apple not built at all" applies to every criterion), so per-criterion gaps are artificial and a heavy
    migration.
  - *Finer area keys* (`android-ui` vs `android-data`) — violates the documented "**stop at area × level**" rule
    and invents a taxonomy only to dodge the overlap check.
  - *Forbid all overlap naively* (the finding's first proposal) — breaks legitimate partial coverage; it is the
    reason this needed a model decision rather than a one-line validator tweak.
