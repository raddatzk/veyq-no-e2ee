---
id: cr-0140
title: Apple VeyqKit test-fixture sync — full apple swift test green (#80 apple)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Finish the apple half of finding #80: bring the apple `VeyqKit` data-layer test fixtures back in sync with the
current generated models so the full `swift test` is green. [cr-0139](cr-0139-crypto-conformance-vectors.md)
compile-fixed apple's `VeyqClient.swift` (to verify crypto on apple); its `ScopesTests`/`ContentTreeTests`/
`MediaTests` were still red on stale mock fixtures — the exact apple twin of the android fixture-sync
[cr-0138](cr-0138-android-client-fixture-sync.md). All fixes are test-side and mechanical.

## Context & constraints

- **Same drift, same fixes as cr-0138** (apple stubs use `[String: Any]` dicts serialized to JSON):
  - `ScopesTests` — the three scope-summary fixtures gained `role`/`nameEpoch`/`currentEpoch`/`grantedEpoch`
    (`ScopeSummary` requires them since the epoch chain, cr-0088/0092/0093); `role="OWNER"`, epochs `0` at a
    fresh personal-scope birth (same values as cr-0138, from the backend).
  - `ContentTreeTests` — the stored node row gained `"epoch": 0` (`NodeSummary.epoch`, 0 = pre-rotation).
  - `MediaTests` — the `listNodeMedia` stub now returns a `MediaListPage` object
    (`{media, mediaRevision, deleteRevision}`) instead of a bare array (the decoder failed with
    `typeMismatch: expected Dictionary, found array`), and each media summary gained the required `updatedAt`.
    No tier-spec assertion needed (apple's MediaTests didn't pin the 400/512 thumbnail the way android's did).
- **No production code changed** — test fixtures only. The root cause is the same as #79/#80: nothing regen+
  builds the native surfaces on a contract change, so apple's data layer + fixtures rotted undetected.
- **Verify:** `cd apple && swift test`.

## Phases

- [x] 1 apple tests — sync the Scopes/Content/Media stub fixtures to the current models. · **Done:** the three
  suites go 10 failures → 0; full `swift test` → **"All tests passed"** (incl. `V1ConformanceTests`). Closes the
  apple half of #80.

## Definition of done

`swift test` green (was 10 failures across ScopesTests/ContentTreeTests/MediaTests); whole apple surface builds
+ tests green; no production code changed; trust-chain guards green. The CI native-surface **job** (#80) is a
separate remaining item.

## Retrospective

- **Mechanical, and identical to cr-0138 — which is the point.** The apple and android data layers drifted the
  same way against the same contracts, so the fixes were the same field-adds + the MediaListPage wrapper.
  Fixing both by hand (twice) is the symptom; the cure is the CI native-surface job (#80) so a contract change
  reds the build in CI, once, instead of rotting until someone next touches each native surface.
- **Next time:** nothing in execution — the android precedent (cr-0138) made this a direct port, and the
  decode-error messages pointed at each missing field in turn.
