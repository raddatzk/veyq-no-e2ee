---
id: cr-0138
title: Android :client test-fixture sync — the #79 remainder (green :client:test)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Finish finding #79: bring the Android `:client` data-layer test fixtures back in sync with the current
generated models so `./gradlew :client:test` is green again. [cr-0137](cr-0137-android-nologin-gate-client-realign.md)
made `:client` *compile*; its MockWebServer tests still failed (10 failures) because the JSON fixtures predate
several contract additions. Scoped first (confirmed no parity bug, no production-code drift) — all fixes are
test-side and mechanical.

## Context & constraints

- **Verified no parity bug before touching anything.** The one behavioural-looking failure —
  `MediaTest`'s thumbnail tier asserted `400` but the code produced `512` — was checked across surfaces: web
  (`media.ts`), Apple (`MediaDeriver.swift`) and Android (`MediaDeriver.kt`) all pin `512` (cr-0070 raised it
  from the legacy 400 for hi-DPI). So the **code is canonical and the test was stale** — a 2-line expectation
  update, not a cross-platform bug.
- **The fixes** (all under `android/client/src/test/.../`):
  - `ScopesTest` — the three scope-summary fixtures gained `role`/`nameEpoch`/`currentEpoch`/`grantedEpoch`
    (required on `ScopeSummary` since the group-scope epoch chain, cr-0088/0092/0093); values sourced from the
    backend (`role=OWNER`, all epochs `0` at a fresh personal-scope birth), not guessed.
  - `ContentTreeTest` — the node serializer gained `"epoch":0` (required on `NodeSummary`; `0` = pre-rotation,
    per the backend default + web fixtures).
  - `MediaTest` — four fixes to the media control-plane dispatcher + assertions: (1) `listNodeMedia` now sends
    `?sinceDelete&state`, so the GET matcher + node extraction strip the query; (2) the GET body is wrapped as
    a `MediaListPage` (`{media, mediaRevision, deleteRevision}`) since the client deserializes that now; (3) the
    media summary fixture gained the required `updatedAt`; (4) the two stale `400`→`512` thumbnail assertions.
- **Root cause = the same contract-drift as the #79 compile break:** nothing regen+builds Android on a contract
  change, so the data layer (and its fixtures) rotted since cr-0048. No production code changed here.
- **Verify:** `cd android && ./gradlew :crypto:test :client:test :app:testDebugUnitTest :app:assembleDebug`.

## Phases

- [x] 1 android :client tests — sync the Scopes/Content/Media fixtures + the two stale tier assertions. ·
  **Done:** `:client:test` **65 passed / 2 skipped / 0 failed**; the full Android suite (`:crypto:test`,
  `:client:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`) BUILD SUCCESSFUL.

## Definition of done

`./gradlew :client:test` green (was 10 failures); the whole Android surface builds + tests green; no production
code changed (fixtures/assertions only); trust-chain guards green. Closes finding #79.

## Retrospective

- **Scoping first paid off.** The tempting read was "the thumbnail tier drifted — a parity bug"; a three-surface
  check showed 512 is canonical and the *test* was stale, so this stayed a mechanical fixture sync instead of a
  code hunt. And the MediaTest `NodeNotFound` looked like a stale node fixture but was actually the dispatcher
  matching a now-query-laden path — tracing the real cause avoided "fixing" the wrong thing.
- **The deeper miss is process, not this diff.** Android silently rotted (compile + fixtures) because no CI
  regenerates + builds it on a contract change. Recorded on #79/cr-0137; the durable fix is a CI job or a
  contracts-change checklist item, worth its own inbox entry if it recurs.
- **Next time:** nothing in execution — the pre-scan + incremental re-runs converged cleanly (10 → 5 → 5 → 0)
  with each step's error message pointing at the next fixture gap.
