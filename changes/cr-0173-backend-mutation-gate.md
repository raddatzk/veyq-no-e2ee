---
id: cr-0173
title: Backend mutation gate — PIT on the pure decision tier (rotate, cascade, rate-limit)
status: planned
satisfies: []
adrs: ["0045", "0024", "0007", "0014"]
invariants:
  - operator-blind E2EE — a broken rotate/consent refactor must never (re-)wrap K_SC onto a non-consenting member
  - self-hostable by default — no unmaintained or successor-closed dependency enters the build (the pitest-kotlin trap, ADR 0045 §1)
---

## Goal

Close the trust chain's last link on the backend: mutation-verify that the security-core **decisions** have tests
whose assertions actually pin behaviour, not merely resolve a ref (ADR 0045). The value lands not by "wiring up PIT"
but by a scoped refactor — lift each rich decision out of its store/handler/filter into a **pure function** (functional
core), leaving the impure caller as an imperative shell, and mutate only those pure functions on the boot-free unit
layer. The rotate/consent archetype is already **spike-validated** (branch `spike/pit-rotate-policy`); this CR
formalizes it and adds the two remaining rich decisions plus the CI gate. Backend-only: no contract, no web change.

## Context & constraints

- **Governing decision:** [ADR 0045](../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md) — the three-layer
  split (§2), the tool choice (§1, **PIT alone, no Kotlin plugin** — measured), CI shape (§4), the honest blind spots.
- **Decisions the refactor must preserve** (the safety net is their green slices): rotation/epoch chain
  [ADR 0024](../docs/decisions/0024-scope-key-rotation.md), last-owner [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md),
  removal cascade / orphan rule [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md).
- **The pattern to mirror** (web pilot, done): `web/stryker.mutate.json` (shared manifest) ·
  `web/stryker.config.mjs` (reads it) · [`scripts/mutation_meta_check.py`](../scripts/mutation_meta_check.py) (the
  diff-aware guard) · [`.github/workflows/mutation.yml`](../.github/workflows/mutation.yml) (PR + push, path-gated,
  incremental cache). The backend gets structural siblings, not shared code (Gradle/Kotlin ≠ npm/vitest).
- **Spike-validated reference:** `git show spike/pit-rotate-policy` — `RotationPolicy` + the dumb `rotate` + the PIT
  wiring + `backend/pitest.scope.json`. Measured: slices green (10/10, 26/26), unit-PIT **100%** (3 DEFAULTS / 5
  STRONGER, zero noise) on Gradle 9.5.1, **no `pitest-kotlin`**.

## The decision this CR realizes — the reframing (spar this first)

ADR §2 names five "fusion points". The spike measured that **only rotate is genuinely store-fused**; the rest already
sit **above** the stores. So the CR is *not* five store refactors — it is a curated set of **pure decision
functions**, realized in the cleanest form the spike found:

- **Pure functions over plain data, not policies over a mocked repository.** The handler/filter does the impure reads
  and passes plain values/lists to a pure function that returns a decision; the caller executes it. `RotationPolicy`
  takes `List<RotationMember>` + grants — **no repository, no mock**. Consequence: **no mocking library enters the
  build** (no MockK/Mockito). This satisfies ADR §2's "Policy" row a fortiori (pure decision over returned data) and is
  strictly simpler than its "mocked repository" phrasing — a realization detail, not an ADR change.
- **In mutation scope — three pure policies:**
  1. `RotationPolicy` (rotate/consent) — epoch bump + the `pendingConsent`-never-granted rule. **Spike-done.**
  2. A membership **cascade + last-owner** policy — the tenant-removal per-scope action selection (PERSONAL→soft-delete,
     GROUP&`count≤1`→soft-delete, else→orphan-transfer) and the `ownerCount ≤ 1` boundary. The richest remaining decision.
  3. A **rate-limit window** policy — the fixed-window arithmetic (`windowStart`, `retryAfter`, over-limit + expiry
     boundaries) lifted out of `AuthRateLimitFilter`; the `ConcurrentHashMap`/`AtomicLong` plumbing stays the shell.
- **Explicitly OUT, with the reason (so the score stays honest):**
  - **Gate ordering** (membership-before-probe) — ADR §2 puts it in the **Slice** row, not Policy: an ordering is a
    side-effect sequence, killed for a wiring reason, not an assertion. Stays a slice test, unmutated.
  - **Trivial one-line gates** — the stale-epoch `!=` (`ScopesHandler.renameGroup`), `isScopeOwner` (a boolean).
    ADR §2: "lifting it buys nothing." Mutating them adds count, not signal.

## Inventory — locators, not tallies (run at implement time; the number's reader is this conversation)

| Target | Locator (a command, re-runnable while code drifts) |
|---|---|
| rotate policy (reference) | `git show spike/pit-rotate-policy:backend/src/main/kotlin/com/veyq/app/features/spaces/scope/RotationPolicy.kt` |
| last-owner boundary sites | `grep -rn 'ownerCount(.*) <= 1\|tenantOwnerCount(.*) <= 1' backend/src/main` |
| cascade decision | `grep -n 'membershipsInTenant\|oldestOtherMember' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt` |
| rate-limit arithmetic | `grep -n '% windowSeconds\|incrementAndGet() >\|windowSeconds -' backend/src/main/kotlin/com/veyq/app/shared/ratelimit/AuthRateLimitFilter.kt` |
| safety-net slices (must stay green) | `grep -rln 'rotate\|ownerCount\|LastOwner\|removeMember\|RateLimit' backend/src/test` |
| boot-free unit tier (where new policy tests join) | `grep -rL '@QuarkusTest' backend/src/test --include='*Test.kt'` |

**Read each as a list, from a second angle where a number decides a cut** (changes/README.md): before freezing the
cascade scope, list the branches `TenantHandler.removeMember` actually takes vs. what ADR 0014 §6 says it *should* —
a disagreement is a question back to this refinement, not a mutant.

## Phases

- [ ] 1 backend — **rotate policy**: adopt the spike-validated extraction (cherry-pick `spike/pit-rotate-policy` or
      re-derive) + PIT plugin wiring + `pitest.scope.json`. · Done when: `./gradlew check` green (slices +
      `RotationPolicyTest`) **and** `./gradlew pitest` = 100% on `RotationPolicy`, zero survivors/NON_VIABLE.
- [ ] 2 backend — **cascade + last-owner policy**: extract the pure action-selection + `ownerCount ≤ 1` decision;
      handlers become shells; add its boot-free unit test (pin values + the message, never a bare truthy). Add the
      class to `pitest.scope.json`. · Done when: `check` green (the removal/cascade slices are the net) **and**
      `pitest` on the policy ≥ its measured high, zero survivors.
- [ ] 3 backend — **rate-limit window policy**: extract the window arithmetic from `AuthRateLimitFilter` into pure
      functions; filter keeps the concurrency shell. Add its unit test + the class to `pitest.scope.json`. · Done
      when: `check` green (`RateLimitedEndpointsTest` + the auth slices) **and** `pitest` on the policy ≥ high.
- [ ] 4 backend/CI — the **gate**: calibrate `break = combined high − margin` (per policy or one combined scope;
      **not 100** — the margin is headroom for a future equivalent mutant, per the web pilot). Enable PIT's history
      file as the incremental cache. Add `scripts/mutation_meta_check_backend.py` (reads `backend/pitest.scope.json`
      + PIT's `mutations.xml`) and `.github/workflows/mutation-backend.yml` (PR + push to main, path-gated, cache).
      Update the inbox #99 row from "pending" → measured. · Done when: the workflow is green on a no-op PR and **bites**
      (snapshot-and-restore a weakened assertion → red), and `for f in scripts/validate_*.py; do python3 "$f"; done` green.
- [ ] N coverage — **none**: this CR `satisfies:` no use-case (a guardrail, not a product behaviour), so
      `docs/coverage/` is unchanged. · Done when: `python3 scripts/validate_coverage.py` green (unmodified).

## Definition of done

`./gradlew check` and `./gradlew pitest` green across all three policies; the mutation score meets the calibrated
`break` (measured `high − margin`, never 100); the `mutation-backend` CI job is green **and bite-proven**; the shared
`pitest.scope.json` is the single mutate-scope source (build + meta-check read it, neither restates it — the
permanent guard against scope drift, promoting the spike's one-shot into a standing check); the full
`scripts/validate_*.py` glob stays green. No mocking dependency, no `pitest-kotlin`, added to the build.

**Two guard nuances to honor, not paper over:**
- PIT's `failWhenNoMutations=true` already fails the build when the *whole* scope mutates nothing. The meta-check adds
  what that misses: a **per-file** assertion — a policy class renamed and dropped from the glob while its siblings
  still mutate would pass `failWhenNoMutations` but be caught here. State that delta in the script's docstring; if it
  turns out `failWhenNoMutations` + `break` genuinely suffice, the CR says so instead of building a redundant guard.
- **Spotted in passing, fix here:** `scripts/mutation_meta_check.py`'s docstring claims Stryker runs diff-scoped via
  `--since` — ADR §4 and `mutation.yml` say the opposite (no `--since` in StrykerJS OSS; the incremental cache is the
  scoping). Correct the docstring while writing its backend sibling, so the two tell one story.

## Retrospective

_(filled at close: planning + execution + process/collaboration misses, both directions, and one forward-looking "next time".)_
