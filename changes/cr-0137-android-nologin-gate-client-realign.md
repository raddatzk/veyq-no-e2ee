---
id: cr-0137
title: Android "No Login" device-security gate + :client compile realignment (audit P2f + #79)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Two coupled changes, landed together because the first blocked the second:

1. **`:client` compile realignment (#79).** The Android data layer no longer compiled on the branch — a media
   contract-drift the hand-written `VeyqClient` was never dragged through since cr-0048. Fixed so `:client` (and
   therefore `:app`) build again.
2. **Android passwordless "No Login" gate (audit P2f, #69).** Forbid enabling the L3 auto-unlock on a device
   with no secure lock screen, a faithful port of Apple's already-shipped guard. L3 stores the random key `K` in
   the Keystore **ungated** (`setUnlockedDeviceRequired` only), which is vacuous without a device credential — so
   `K`, hence the Master Key, would be recoverable with no authentication. The gate closes exactly that hole.

## Context & constraints

- **`:client` realignment — 4 mechanical call-site fixes** (`VeyqClient.kt`), each an adjustment to the current
  generated model, no new behaviour:
  - `listNodeMedia` now returns a `MediaListPage` (`media` + `mediaRevision`/`deleteRevision`/`mediaIds`) rather
    than a bare list → `listMedia` maps over `.media`.
  - `MediaRequestItem` gained a `bucketId` param → switch to named args so `derivatives` lands on the right field.
  - The login/refresh `refreshToken` became optional on the wire (it rides a cookie in the web remember-me
    transport, cr-0052); the Android data layer never opts into cookies, so a null body token is a protocol
    error → `?: throw` (AuthenticationFailed on login, RefreshRejected on refresh).
- **The gate mirrors Apple at both layers** (`apple/App/AppModel.swift:137` + `DeviceSecurityChooser.swift:58`):
  - **Enforcement (mandatory):** `AppModel.applyLevel` NO_LOGIN branch throws `if (!DeviceSecureGate.isSecure(...))`
    before `storeKeyUngated`, so the existing `runCatching{…}.onFailure{ deviceTrustFailed = true }` surfaces the
    failure UX and the unlock still lands at L0 — nothing auth-less is written on a non-secure device.
  - **UI (defense in depth):** `DeviceSecurityChooser` disables the NO_LOGIN row when `!deviceSecure` (reusing
    the "Not available" badge), fed from `UnlockScreen`. The gating decision is extracted to a pure top-level
    `deviceLevelAvailable(level, biometricAvailable, deviceSecure)` so it unit-tests on the JVM.
  - `DeviceSecureGate` is a small capability object (`KeyguardManager.isDeviceSecure`) mirroring the existing
    `BiometricGate`.
- **Invariant — operator-blind: strengthened.** The gate stops key material from being recoverable without user
  authentication on a lock-screen-less device; nothing new is sent to the operator.
- **Scope boundary — the rest of #79 is NOT in this CR.** `:client` now *compiles* and `:app` is green, but
  `./gradlew :client:test` is still **red** from **pre-existing stale MockWebServer fixtures** (`ScopeSummary`
  missing `role`/`*Epoch`, `NodeSummary` missing `epoch`, a cascading media `NodeNotFound`) — a broader
  data-layer fixture-sync, tracked as the remaining #79 work, deliberately deferred rather than half-done here.
- **Verify:** `cd android && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug` green; `:client`
  compiles. (`:client:test` red — see #79 remainder.)

## Phases

- [x] 1 android :client — 4 API-realignment fixes so `:client` compiles. · **Done:** `:client:compileKotlin`
  BUILD SUCCESSFUL.
- [x] 2 android :app — `DeviceSecureGate`; the `AppModel.applyLevel` NO_LOGIN enforcement guard; the
  `DeviceSecurityChooser` UI gate + pure `deviceLevelAvailable`; `UnlockScreen` wiring; `DeviceLevelAvailabilityTest`
  (JVM, 3 cases). · **Done:** `:app:testDebugUnitTest` (gate test green, 3 cases) + `:app:assembleDebug` BUILD
  SUCCESSFUL; coverage `unsupported-levels-hidden` extended for the No-Login case; `validate_coverage.py` green.

## Definition of done

`:app:testDebugUnitTest` + `:app:assembleDebug` green; the NO_LOGIN level is blocked (UI + enforcement) without a
secure lock screen; `:client` compiles; the set-device-security coverage cites the gate test; trust-chain guards
green. (`:client:test` fixture-sync explicitly out of scope → #79.)

## Retrospective

- **The audit item looked like a clean parity port; landing it exposed a red Android build.** #69 was "add one
  guard mirroring Apple," but the surface wouldn't even compile — `:client` had silently drifted from the
  contracts (media list-page wrapper, request-item `bucketId`, nullable refresh token) and hadn't been rebuilt
  since cr-0048. Root cause: nothing compiles Android on a contract change, so drift accumulates invisibly until
  someone next touches the surface. **Next time:** a contract-change CR (or CI) should regen + compile Android,
  so the data layer can't rot into a build-red surprise.
- **Held the scope line at the "beyond mechanical" point.** The 4-line compile fix was mechanical and got the
  gate verifiable; the `:client` *test* fixtures are stale across scopes/content/media — a broader sync — so I
  fixed + flagged it (#79 remainder) rather than half-syncing fixtures inside a security CR.
- **Kept the gate honestly verified:** the enforcement guard isn't JVM-constructable (it's in an
  `AndroidViewModel`), so I unit-tested the pure gating decision and leaned on the exact-Apple-parity of the
  enforcement + the existing instrumented `NoLoginAutoRestoreUiTest` for the rest, rather than claim a coverage
  I didn't have.
