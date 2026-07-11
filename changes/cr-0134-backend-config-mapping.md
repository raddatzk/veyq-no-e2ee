---
id: cr-0134
title: Backend @ConfigMapping — one typed VeyqConfig for all veyq.* config (audit P3c)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Fulfil styleguide §8 ("project-owned `veyq.*` config is modeled with a complete `@ConfigMapping` class, not
scattered per-value properties") for the backend. Before this CR every `veyq.*` value was read via an individual
`@ConfigProperty` — 28 injection sites across 16 classes, with three keys (`veyq.tasks.max-attempts`,
`veyq.scope.delete.retention`, `veyq.blob-store.endpoint`) duplicated verbatim across 2–3 classes. This adds one
typed `VeyqConfig` `@ConfigMapping` interface modeling the whole `veyq.*` surface and rewires every site to read
from it. No behavior change, no property-key change — `application.properties` (and the `%dev`/`%test`
overrides) are untouched; only the Kotlin read-sites change.

## Context & constraints

- **One root `@ConfigMapping(prefix = "veyq")` interface** (`shared/config/VeyqConfig.kt`) with nested groups
  (version, capabilities, tenant, session, scope, media, blob, blob-store, tasks, ratelimit, share). Method
  paths map to the existing kebab-case keys (`tasks().maxAttempts()` ⇒ `veyq.tasks.max-attempts`), so the keys
  are unchanged. `@WithDefault`s mirror `application.properties` + the previous annotation defaults.
- **Complete inventory, incl. the `*.purge.every` / `*.reconcile.every` intervals.** Those 5 keys are consumed
  by their sibling `@Scheduled(every = "{…}")` beans as raw property expressions (a `@Scheduled` string can't
  read a mapping), so those annotations are left exactly as-is — but the keys are also declared in `VeyqConfig`
  (a reused `Interval` group) so the interface is the complete typed model of `veyq.*`.
- **Framework carve-out handled, not skipped — `AuthRateLimitFilter`.** A JAX-RS `@Provider` is instantiated too
  early in Quarkus boot to take `VeyqConfig` as a CDI dependency: **both** constructor and field injection of the
  `@ConfigMapping` bean fail config-mapping registration (`SRCFG00027 … Could not find a mapping`) and abort the
  whole app boot. Rather than fall back to scattered `@ConfigProperty` (which styleguide §8 would permit for
  framework config), the filter reads the mapping via a **lazy programmatic lookup**
  (`(ConfigProvider.getConfig() as SmallRyeConfig).getConfigMapping(VeyqConfig::class.java).ratelimit().auth()`)
  on first request — no CDI injection point on the `@Provider`, so the boot graph is unaffected, and `VeyqConfig`
  stays the single source for these knobs. Documented inline.
- **Tests:** two pure-JVM unit tests that constructed beans directly (`TenantInviteMailerDevFileTest`,
  `CapabilityRegistryTest`) now build a real mapping via a new `TestVeyqConfig` helper
  (`SmallRyeConfigBuilder` + the Quarkus `DurationConverter`, since the `@WithDefault`s use Quarkus's relaxed
  `1h` form which plain SmallRye rejects). The slice tests (`@QuarkusTest`) get the real CDI-registered mapping,
  unchanged.
- **No invariant at risk** — pure config plumbing; no key, default, or behavior changed. `@Scheduled` intervals
  and `application.properties` are byte-identical.
- **Verify:** `cd backend && ./gradlew check`.

## Phases

- [x] 1 backend — `VeyqConfig.kt` (+ `TestVeyqConfig.kt`); rewire all 28 sites in 16 classes to the mapping;
  `AuthRateLimitFilter` via lazy programmatic lookup; fix the 2 direct-construction unit tests. · **Done:**
  `./gradlew clean check` → **BUILD SUCCESSFUL**, 298 tests / 0 failures / 1 skipped; `application.properties`
  unchanged; trust-chain guards green.

## Definition of done

`./gradlew check` green; every `veyq.*` value read through `VeyqConfig` (or, for the one JAX-RS provider, its
programmatic lookup of the same mapping); no property key or default changed; trust-chain guards green. No
`satisfies:` — a structural refactor, not a use-case.

## Retrospective

- **Execution: I under-scoped the file list and the subagent's "green" masked a real boot break.** My refactor
  spec to the subagent listed 15 files but the inventory had 16 — I dropped `AuthRateLimitFilter` (its 2 sites
  are recent, from cr-0130). The subagent correctly flagged the omission and reported BUILD SUCCESSFUL for the
  15; but when I folded in the 16th, the app failed to boot globally (`SRCFG00027`) — and a clean rebuild showed
  the subagent's 15-file tree, had I added the 16th there, would have failed too. Root cause: I trusted a
  sub-run's "green" without re-running a clean `check` myself after every edit. **Next time:** after any
  hand-edit on top of a delegated change, run `./gradlew clean check` before believing the tree is green —
  incremental/sub-agent build state is not a substitute.
- **Design: hit and worked around a genuine Quarkus limitation rather than papering over it.** The `@Provider` +
  `@ConfigMapping` injection incompatibility cost three build cycles (constructor → field → programmatic lookup)
  to diagnose, but the isolation experiment (revert the one file → green) pinned it cleanly, and the lazy-lookup
  fix keeps `VeyqConfig` complete instead of leaving 2 scattered `@ConfigProperty` reads.
- **Good:** delegating the 16-file mechanical grind to a subagent with an exact accessor map was the right call
  — the compiler + slice tests verified it, and the only defects were at the two edges I owned (the missing file,
  the provider gotcha), not in the bulk mechanical work.
