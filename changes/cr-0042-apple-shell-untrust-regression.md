---
id: cr-0042
title: Apple shell — restore the device-trust status + untrust control (regression from cr-0034/0035)
status: planned
satisfies: [uc.account.set-device-security, uc.account.trust-device, uc.account.biometric-unlock, uc.account.auto-unlock]
adrs: ["0005"]
invariants: []
---

## Goal

Restore the **device-trust status + "Stop trusting this device" untrust control** on the Apple Memory shell.
When cr-0034/0035 replaced the unlocked-landing stub with the shell ([HomeView](../apple/App/HomeView.swift)),
that control silently disappeared — `AppModel.untrust()` still exists but is wired to no UI, and the two
device-trust UITests (`RoundTripUITests.testTrustDeviceLifecycle` / `testNoLoginAutoRestoreLifecycle`) still
assert on the removed `Unlocked` / `Stop trusting this device` elements, so they cannot pass. The four account
use-cases they back are therefore only *nominally* e2e-covered on Apple. This CR adds the control back (in the
shell's `sidebar`/`profileBar`, shown when `deviceTrusted`) and **un-stales** those two UITests, mirroring the
Android fix in [cr-0039](cr-0039-android-memory-shell.md). Pure client UI + test repair; no contract/crypto
change.

## Context & constraints

- The regression + the cross-platform decision are recorded in
  [cr-0039 §Context](cr-0039-android-memory-shell.md) ("don't replicate the regression"). Android restored the
  control in its spaces rail; this is the Apple twin.
- Data layer is ready: `AppModel.untrust()` + `@Published deviceTrusted` already exist (cr-0019/0020) — only
  the SwiftUI control + the test markers are missing.
- Reuse the existing shell structure: add the control to `HomeView.profileBar`/`sidebar` (Apple's parity of
  Android's rail), `accessibilityIdentifier("Stop trusting this device")`, calling `app.untrust()`. On compact
  the sidebar is a drawer — the UITests must surface it before tapping (drawer-aware), like Android's
  `openRail()`.
- Un-stale the two UITests: the home marker `app.staticTexts["Unlocked"]` → the shell's locale-independent
  `Create` toolbar button (already used by `testConnectSignInUnlock`); reach untrust/sign-out via the sidebar.
- Durable truth: [uc.account.set-device-security](../docs/features/account/use-cases/uc.account.set-device-security.md),
  [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md),
  [uc.account.biometric-unlock](../docs/features/account/use-cases/uc.account.biometric-unlock.md),
  [uc.account.auto-unlock](../docs/features/account/use-cases/uc.account.auto-unlock.md);
  device unlock levels per [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md).

## Phases

- [ ] 1 shell control — add the trusted-status + "Stop trusting this device" control to `HomeView`
  (`sidebar`/`profileBar`, shown when `app.deviceTrusted`), wired to `app.untrust()`. · Done when: `xcodebuild`
  macOS + iOS Simulator green.
- [ ] 2 un-stale the UITests — point `testTrustDeviceLifecycle` / `testNoLoginAutoRestoreLifecycle` at the
  shell (home marker = `Create`; untrust/sign-out via the sidebar, drawer-aware on compact). · Done when: the
  env-gated UITests drive the lifecycle on an iPhone **and** an iPad destination against a live instance, both
  `TEST SUCCEEDED`.
- [ ] 3 coverage — confirm the four account use-cases' Apple e2e refs now resolve to passing tests; no YAML
  change expected (the refs already point at these methods). · Done when: `python3 scripts/validate_coverage.py`
  green.

## Definition of done

The Apple shell shows the trusted state + an untrust control when the device is trusted; `app.untrust()` is
reachable again. `xcodebuild` macOS + iOS green; the two device-trust UITests pass on iPhone + iPad against a
live instance; coverage green. Parity with Android (cr-0039) restored.

## Retrospective

_(to be filled on close)_
