---
id: cr-0015
title: Apple universal client — login + unlock (macOS + iOS + iPadOS)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.login, uc.account.unlock]   # the Apple surface of these (web already has them)
adrs: [0003, 0004, 0005, 0009, 0010]
invariants: [operator-blind]
---

## Goal

The first native **Apple client surface**: one **universal SwiftUI app** (macOS + iOS + iPadOS, one target)
that connects to an **already-bootstrapped** instance, **logs in** (operator-blind challenge-response, ADR
0003) and **unlocks** the vault (key-material → unlock KEK → unwrap Master Key + identity private, ADR 0005),
reusing the cr-0014 `VeyqCrypto` and the `contracts/`. The UI is **adaptive** across iPhone / iPad / Mac
(size classes, not fixed phone layout). Setup stays browser-only — native never bootstraps. The built
**macOS screenshots are committed** as the cross-platform design reference for the Windows/Linux heads (ADR 0010).

**tvOS is a declared target platform, but gets no UI head in this CR.** The shared SPM libraries
(`VeyqCrypto` / `VeyqApi` / `VeyqKit`) compile for tvOS from day one (cost is ~free — they are platform-
agnostic), so a future tvOS head reuses them unchanged. The tvOS UI is deferred because **login + unlock on a
TV remote is the wrong interaction** — entering the high-entropy Secret Key on a remote is UX-folter, and a
raw text field there would be "inventing UI". A TV signs in via **device-handoff (passkey / QR)**, which lives
in the deferred `trust-device` / passkey use-cases — so the tvOS head follows *those*, not this CR.

**Scope:** login + unlock only, on the macOS / iOS / iPadOS heads (+ tvOS-compatible libs).
**Deferred:** trust-device, passkey, recovery, sessions, content/Memory, **the tvOS UI head**.

## Context & constraints

- Reuses **`VeyqCrypto`** (cr-0014): `deriveAuthSalt` / `deriveAuthKeypair` / `authSign` (login),
  `kekPasswordSecret` + `symUnwrap` (unlock). No new crypto.
- **API-first:** the Swift client is **generated** from `contracts/api/{setup,auth,account}.openapi.yaml`
  (never hand-write DTOs). Generator decided in phase 1 — **swift-openapi-generator** (Apple, SPM plugin)
  is the default; `openapi-generator` swift5 is the fallback.
- **Universal app, native everywhere** (ADR 0010): one SwiftUI target for macOS 26+ / iOS 26+ / iPadOS. The
  app shell / `.xcodeproj` is generated from a committed source (**xcodegen `project.yml`**) so the project
  is reproducible, not a hand-edited pbxproj. Most logic lives in SPM library targets; the app is a thin head.
- **tvOS libs only** (ADR 0010 matrix amended): `VeyqCrypto` / `VeyqApi` / `VeyqKit` declare `tvOS 26+` in
  `Package.swift` and stay buildable for it (a `swift build` / vector test for the tvOS-compatible subset is a
  phase-1 done-when). **No tvOS app head** in this CR — see Goal for why (TV login = passkey/QR handoff).
- **Reuse legacy UI/UX — never invent** (ADR 0010 / `apple/CLAUDE.md`): orient on `legacy/iOS/Veyq`
  (SwiftUI app) + `legacy/apple` (`VeyqApiModels`/`Login.swift`), read-only; port screens, swap the data
  layer to the generated client + `VeyqCrypto`.
- **Operator-blind:** the password is never sent (challenge-response); the vault (Master Key + identity priv)
  is **in-memory only**, cleared on background/sign-out — the Apple analog of the web `vault.ts`.
- **Verify:** macOS app builds + runs locally; iOS/iPad via the simulator (`xcrun simctl`). Screenshots come
  from the real running app.
- **Parity ledger** (this is the first CR under ADR 0010's all-surfaces rule): web ✅ · **apple ✅**
  (macOS/iOS/iPadOS heads built; data layer + full UI round-trip green against a live instance; tvOS-ready
  libs build, tvOS UI head pending; macOS desktop screenshots pending an Accessibility grant) · android /
  windows / linux = pending (login+unlock to follow per surface).

## Phases

- [x] **1 — client + scaffold.** ✅ swift-openapi-generator **build plugin** generates `SetupAPI` / `AuthAPI`
  / `AccountAPI` from the specs (symlinked to `contracts/` → single source of truth, no committed generat);
  `VeyqApi` facade re-exports them + a URLSession transport; `VeyqKit` holds the orchestration stub; xcodegen
  `project.yml` builds the universal app (macOS+iOS+iPadOS). `Package.swift` declares macOS 26 / iOS 26 /
  **tvOS 26** (greenfield — current-OS only; bumped from 13/16 in phase 4). **Verified:** `swift test` 10/10
  (incl. crypto vectors) · app **macOS** ✅ · app **iOS
  simulator** ✅ · libs **tvOS** ✅ (`xcodebuild -scheme VeyqKit -destination 'generic/platform=tvOS Simulator'`).
  Notes: plugin-only targets need ≥1 `.swift` file (else SPM drops them); headless `xcodebuild` needs
  `-skipPackagePluginValidation`; the generated `.xcodeproj` + plugin `GeneratedSources/` are gitignored.
- [x] **2 — login (data layer).** ✅ `VeyqKit` login mirrors web `features/account/login`: `POST
  /api/login/challenge` (the challenge carries the instanceSalt — no separate `GET /api/setup` needed) →
  `deriveAuthSalt` → `deriveAuthKeypair` → `authSign(nonce)` → `POST /api/login/verify` → store opaque
  access+refresh in an in-memory **`Session`** actor; `refresh()` rotates (drops tokens on rejection),
  `logout()`, and a `withRefresh` rotate-on-`.sessionExpired`-and-retry helper (ADR 0004). `BearerMiddleware`
  injects the access token per request. **Verified:** `swift test` 14/14 — the headline `LoginTests` drives
  the full chain against a `StubTransport` (no server) and asserts the signature the client sent **verifies**
  against the independently-derived keypair (operator-blind, ADR 0003), plus refresh-rotation / no-session /
  withRefresh-retry cases.
- [x] **3 — unlock (data layer).** ✅ `VeyqKit` unlock mirrors web `features/account/unlock` (PASSWORD floor,
  2SKD): `GET /api/account/key-material` (via `withRefresh`) → pick the PASSWORD wrap → `decodeKdfParams` →
  `kekPasswordSecret(secretKey, password)` → `symUnwrap` the Master Key (`master-key`) → `symUnwrap` the
  identity private (`identity-priv`) into an in-memory **`Vault`** actor (best-effort zeroed, cleared on
  logout). Identity private is held as **raw unwrapped bytes** (ML-KEM parsing deferred — login+unlock only
  need to hold it). Errors: `.unlockUnavailable` (no PASSWORD wrap), `.invalidSecretKey`, `.unlockFailed`
  (AEAD tag mismatch — one generic failure). **Verified:** `swift test` 19/19 — `UnlockTests` builds key
  material with the same V1 primitives and asserts unlock recovers the **exact** Master Key + identity bytes;
  wrong-Secret-Key → `.unlockFailed` + vault stays locked; DEVICE-only → `.unlockUnavailable`; key-material
  401 → refresh → retry unlocks; logout clears the vault.
- [x] **4 — SwiftUI UI (adaptive, macOS/iOS/iPadOS).** ✅ Ported the legacy
  native auth UI onto `VeyqKit`, made genuinely cross-platform (native macOS + iOS, **not** Catalyst — the
  legacy `UIColor`/`UIKeyboardType` bits replaced with `#if canImport(UIKit)/AppKit` equivalents):
  `VeyqStyle` (tokens + `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox`), `AppModel` (connect → sign-in →
  home flow controller), `ConnectView` (instance URL + `setupRequired()` gate), `SignInView` (email +
  password + Secret Key → `login` then `unlock`, secrets dropped after submit), `HomeView` (unlocked state +
  sign-out). One SwiftUI tree, adaptive (cards cap width, not stretched). **Verified:** `swift test` 19/19;
  `xcodebuild` **macOS** ✅ + **iOS 26 simulator** ✅; app **installs + launches on an iOS 26 sim** and renders
  the Connect screen natively (screenshot). The live login → unlock round-trip is proven in phase 5 (the
  data layer via `LiveRoundTripTests`, the full UI via `RoundTripUITests`).
- [x] **5 — screenshots + docs + live round-trip.** ✅ Stood up a real instance (MariaDB + `quarkusDev`),
  seeded a Server Owner with a known Secret Key (`web/scripts/seed-owner.mts`, reusing the cold-account-birth
  crypto), and proved the round-trip **for real**: `LiveRoundTripTests` (data layer over URLSession against
  the live backend) + `RoundTripUITests` (the full **connect → sign-in → unlock** UI, env-gated, ad-hoc
  signed). Screenshots committed under `docs/design/screenshots/apple/` (`ios-01-connect` / `ios-02-signin` /
  `ios-03-home`) with a reproducer README. Recorded the **apple** surface in the `uc.account.{login,unlock}`
  coverage files (`validate_coverage.py` green). **Caveat:** the screenshots are from the **iOS 26 simulator**
  — the macOS desktop captures the design reference ideally wants need a one-time **Accessibility** grant
  (CLI-launched macOS UI-test runners are TCC-killed without it; same wall as computer-use). The design is
  identical across heads (ADR 0010), so the iOS captures stand in until the grant is available; the README
  documents the one-line re-run to add `macos-*`.

## Definition of done

A universal Apple app (macOS + iOS + iPadOS, one SwiftUI target, adaptive layout) connects to a bootstrapped
instance, logs in via operator-blind challenge-response, and unlocks the in-memory vault — all crypto from
`VeyqCrypto`, all DTOs from the generated client, the password never sent, the vault never persisted. The
shared SPM libraries additionally build for **tvOS** (no tvOS UI head — deferred to passkey/QR handoff). macOS
+ iOS-simulator verified; screenshots committed as the Windows/Linux design reference. Parity ledger: apple ✅
(tvOS head pending), android/windows/linux pending. Keep this file as a record after merge.
