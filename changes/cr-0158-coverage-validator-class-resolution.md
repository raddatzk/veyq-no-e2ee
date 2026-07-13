---
id: cr-0158
title: Coverage validator — resolve backend/apple/android refs by class+method, fix the /build/ exclusion (audit P1b core)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Harden `scripts/validate_coverage.py` so a `ClassName.method` test-ref is resolved against **that class**,
not a flat set of every method name on the surface (audit finding P1b, [inbox #62](../docs/inbox.md)). Today a
backend/apple/android ref is accepted as long as *some* test class anywhere has a method of that bare name — so
a ref pointing at the wrong class silently passes. Three generic backend method names collide across 5–6 classes
(`unauthenticatedIs401` ×6, `nonMemberIs404` ×5, `missingTokenIsGeneric401` ×2), and **6 refs across 5 coverage
files** ride those ambiguous names (all correct today, but unguarded). Also fix a determinism bug in the same
code path: `android_test_methods` is meant to skip compiled output under `*/build/*` but its substring filter
lets generated files at `android/client/build/generated/openapi/**/src/test/kotlin/**` into the method set, so
the set depends on local build state (differs between a working tree and a fresh CI checkout).

This is the **core** of P1b. The other two P1b sub-findings are deliberately split out (they need decisions this
CR shouldn't pre-empt): the `coverage`/`gaps` overlap (inbox #81) turned out to be *legitimate partial
coverage*, not rot — its fix is a coverage-model change, not a validator tweak — and the CI-vs-live level
distinction (inbox #82) is its own refinement.

## Context & constraints

- Tooling/guardrail only — touches `scripts/validate_coverage.py`. **No use-case satisfied, no ADR**
  (`satisfies:`/`adrs:` empty). Not a contract/crypto change.
- The validator is one of the three trust-chain guards run in CI (`changes/README.md`); it must stay
  deterministic and **green on the current tree with zero data changes** — all 6 ambiguous refs already name
  the correct class (each `ClassName` is a real test-file stem containing the method), so class-based resolution
  keeps them passing while catching any *future* misplacement.
- Resolution model (confirmed against the corpus): **every referenced test class name == its file stem** on all
  three surfaces (backend/apple/android). So `ClassName.method` resolves as: a test file whose stem is
  `ClassName` must exist **and** contain `method`. Per-file helper/stub/companion classes (e.g. `Session` in
  `LoginSliceTest.kt`, stub structs in apple `*Tests.swift`) are the *source* of the flat-set collisions but are
  **never ref targets** — they only need to be ignored, which stem-keying does for free (their methods lump
  under the file stem; harmless because no ref names them).
- File-path refs are unchanged: `http`/`web` (path exists) and `apple`/`android` refs containing `/` (path
  exists) keep their current handling. Only the bare `ClassName.method` form changes.

### Changes

1. **Class-scoped resolution.** Replace the three flat method-name sets (`backend_test_methods` /
   `apple_test_methods` / `android_test_methods`) with a **stem → {methods}** map per surface (key = test-file
   stem, value = the `fun`/`func` names in that file). Rewrite `check_ref` for `backend`/`apple`/`android`
   non-path refs to `rpartition('.')` into `class`+`method` and require: (a) a test file with that stem exists,
   (b) it contains `method`. Distinct errors for "test class not found: `Class`" vs "test method not found:
   `Class.method`". A backend/apple/android ref with no `.` and no `/` is malformed → explicit error (expected
   `ClassName.method`). *(Pre-check during impl: grep confirms no bare-method refs exist today; if any surface,
   they get class-qualified as part of this CR.)*
2. **Real `/build/` exclusion.** In the android source scan, add `"/build/" not in str(kt)` so the generated
   `client/build/generated/openapi/**/src/test/**` files are excluded regardless of local build state. (Backend
   and apple scan from `backend/src/test` / `apple/Tests` + `apple/UITests`, which have no `build/` beneath them
   — android is the only leak because it rglobs from the repo root and filters by substring.)
3. **Stem-uniqueness guard.** Class-scoped resolution keys on the file stem, so it silently assumes stems are
   unique within a surface — two files sharing a stem would merge their methods under one key and re-open the
   cross-class hole this CR closes. That invariant holds today (0 collisions on all three surfaces) but was
   unenforced, so the map builder now reports any colliding stem (naming both paths) as a validation error
   instead of assuming it away. *(Not in the original finding text; added to make the CR's core invariant
   self-enforcing rather than merely assumed.)*

### Out of scope

- **`coverage`/`gaps` overlap (P1b sub-finding, → inbox #81).** The 16 files where an area is in both are
  *legitimate partial coverage* ("data layer covered, UI not built yet"), not stale gaps — the root cause is
  that `coverage` is per-criterion while `gaps` is a flat per-file/area map, so the two granularities can't be
  compared. Fixing that is a coverage-schema decision (per-criterion gaps vs finer area keys), refined
  separately — **not** the naive "forbid overlap" the finding first proposed.
- **CI-vs-live level distinction (P1b sub-finding, → inbox #82).** `level: e2e` conflates IDE-only `.http`
  files, env-gated live smokes (`VEYQ_E2E`/`VEYQ_S3_E2E`/`VEYQ_LIVE_BASE`, skipped by default), and native UI
  tests with no CI job. Distinguishing CI-gated from live-only coverage is its own refinement.
- No change to `http`/`web` path resolution, to the orphan-coverage / `status: planned` / gap-reason checks, or
  to any coverage YAML data.

### Verify

- `python3 scripts/validate_coverage.py` **green** on the unchanged tree (proves no false negatives introduced:
  the 6 ambiguous refs + all others still resolve).
- **Drift-injection (manual, matching cr-0144):** temporarily repoint one ambiguous ref at a *different* class
  that also has the method — e.g. `uc.memory.browse.yaml` `NodesSliceTest.nonMemberIs404` →
  `MediaSliceTest.nonMemberIs404` — and confirm the validator now **fails** ("test method not found" / correct
  class mismatch) where the flat set previously passed; revert. Also flip a ref's class to a non-existent stem
  → "test class not found". Revert both.
- **Determinism check:** with `android/client/build/` present (post-Gradle-build) and absent (clean), the
  validator result is identical — the generated `*/build/*` test files no longer enter the method map.
- `python3 scripts/validate_changes.py` + `python3 scripts/validate_doc_links.py` green (this CR's frontmatter +
  links resolve).

## Phases

Single-phase (tooling only, no data migration).

- [x] 1 tooling — `scripts/validate_coverage.py`: stem-scoped `ClassName.method` resolution (backend/apple/
      android) + real `*/build/*` exclusion in the android scan + stem-uniqueness guard · Done when:
      `validate_coverage.py` green unchanged, the drift-injection repoint **fails** as expected (then reverted),
      the result is identical with/without `android/client/build/` present, and an injected duplicate stem
      **fails** the collision guard (then reverted).

## Definition of done

`scripts/validate_coverage.py` resolves every backend/apple/android `ClassName.method` ref against the named
class (a misplaced ref now fails), the android method scan excludes `*/build/*` deterministically, non-unique
test stems fail the collision guard (keeping class-scoped resolution sound), the validator is **green on the
current tree with no coverage-data changes**, and the drift-injection + determinism checks in Verify pass.
`validate_changes.py` + `validate_doc_links.py` green. Inbox #62 updated to record the split (#81 gaps-model,
#82 CI-vs-live).

## Retrospective

- **The plan's own drift-injection example was wrong — and catching that was the point.** The CR suggested
  repointing `NodesSliceTest.nonMemberIs404` → `MediaSliceTest.nonMemberIs404` to prove the fix fails, but
  `MediaSliceTest` genuinely *has* `nonMemberIs404`, so class-scoped resolution (correctly) still passes it —
  it demonstrates nothing. The meaningful drift target is a class that **lacks** the method: `LoginSliceTest`
  exists but has no `nonMemberIs404`, so repointing there fails with "test method not found" where the old flat
  set passed (that *is* the silent-pass bug P1b describes). Lesson: a drift-injection that flips to another
  *valid* resolution can't prove a resolver got stricter — the injected ref has to be one the new rule rejects
  and the old rule accepted.
- **Pre-flight dry-run before touching the resolver paid off.** Replaying the new stem→methods logic over all
  435 refs *before* editing (0 failures) de-risked the "green on the unchanged tree" invariant up front, so the
  edit itself was mechanical and the post-edit run was a confirmation, not a discovery.
- **The `/build/` leak was bigger than the flat-set risk suggested.** 85 phantom stems (91 files) from
  `client/build/generated/openapi/**` were entering the map — but 0 of them collided with a *real* test stem
  today, so the bug was latent (build-state-dependent set, future-collision risk) rather than an active false
  pass. Filtering `/build/` shrank the android map from 103 → 18 stems and made it provably independent of local
  build state. Stem-keying also made the old per-file helper/stub collisions (the flat-set root cause) a
  non-issue for free — helpers lump under their file stem and are never ref targets.
- **Nothing broke; the interactive `cp -i` alias did bite once.** The manual drift revert via `cp` hit the
  shell's `cp -i` overwrite prompt and silently no-op'd — caught it immediately on the re-run (still red) and
  reverted cleanly via `git checkout`. Reminder to prefer `git checkout --` for revert-after-injection over
  `cp` on this machine.
- **The one gap the plan didn't name: the stem-uniqueness assumption was load-bearing but unguarded.** Every
  ref resolves via `stem → {methods}`, which is only sound while stems are unique per surface — a future second
  `NodesSliceTest.kt` in another path would merge method sets and silently re-open the cross-class hole. Verified
  0 collisions today, then added change #3 so the builder *fails* on any collision (proven with an injected
  duplicate) rather than leaving the invariant implicit. Lesson: when a fix's correctness rests on a structural
  assumption ("class == unique stem"), guard the assumption in the same CR — an unenforced precondition is the
  same latent-drift shape the CR set out to kill.
