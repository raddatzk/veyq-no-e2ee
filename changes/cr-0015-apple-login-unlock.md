---
id: cr-0015
title: Apple universal client — login + unlock (macOS + iOS + iPadOS)
status: planned          # planned → in-progress → done (then keep as a record)
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
- **Universal app, native everywhere** (ADR 0010): one SwiftUI target for macOS 13+ / iOS 16+ / iPadOS. The
  app shell / `.xcodeproj` is generated from a committed source (**xcodegen `project.yml`**) so the project
  is reproducible, not a hand-edited pbxproj. Most logic lives in SPM library targets; the app is a thin head.
- **tvOS libs only** (ADR 0010 matrix amended): `VeyqCrypto` / `VeyqApi` / `VeyqKit` declare `tvOS 16+` in
  `Package.swift` and stay buildable for it (a `swift build` / vector test for the tvOS-compatible subset is a
  phase-1 done-when). **No tvOS app head** in this CR — see Goal for why (TV login = passkey/QR handoff).
- **Reuse legacy UI/UX — never invent** (ADR 0010 / `apple/CLAUDE.md`): orient on `legacy/iOS/Veyq`
  (SwiftUI app) + `legacy/apple` (`VeyqApiModels`/`Login.swift`), read-only; port screens, swap the data
  layer to the generated client + `VeyqCrypto`.
- **Operator-blind:** the password is never sent (challenge-response); the vault (Master Key + identity priv)
  is **in-memory only**, cleared on background/sign-out — the Apple analog of the web `vault.ts`.
- **Verify:** macOS app builds + runs locally; iOS/iPad via the simulator (`xcrun simctl`). Screenshots come
  from the real running app.
- **Parity ledger** (this is the first CR under ADR 0010's all-surfaces rule): web ✅ (already built) ·
  **apple = this CR** (macOS/iOS/iPadOS heads + tvOS-ready libs; tvOS head pending) · android / windows /
  linux = pending (login+unlock to follow per surface).

## Phases

- [x] **1 — client + scaffold.** ✅ swift-openapi-generator **build plugin** generates `SetupAPI` / `AuthAPI`
  / `AccountAPI` from the specs (symlinked to `contracts/` → single source of truth, no committed generat);
  `VeyqApi` facade re-exports them + a URLSession transport; `VeyqKit` holds the orchestration stub; xcodegen
  `project.yml` builds the universal app (macOS+iOS+iPadOS). `Package.swift` declares macOS 13 / iOS 16 /
  **tvOS 16**. **Verified:** `swift test` 10/10 (incl. crypto vectors) · app **macOS** ✅ · app **iOS
  simulator** ✅ · libs **tvOS** ✅ (`xcodebuild -scheme VeyqKit -destination 'generic/platform=tvOS Simulator'`).
  Notes: plugin-only targets need ≥1 `.swift` file (else SPM drops them); headless `xcodebuild` needs
  `-skipPackagePluginValidation`; the generated `.xcodeproj` + plugin `GeneratedSources/` are gitignored.
- [ ] **2 — login (data layer).** `VeyqKit` login: `GET /api/setup` (instanceSalt) → `deriveAuthKeypair` →
  `POST /api/login/challenge` → `authSign(nonce)` → `POST /api/login/verify` → store opaque access+refresh in
  an in-memory `Session`; a `withRefresh` 401-retry helper (ADR 0004). **Done when:** a test signs in against
  a fresh instance (or a stubbed client) and holds a session.
- [ ] **3 — unlock (data layer).** `GET /api/account/key-material` → `kekPasswordSecret(secretKey, password)`
  → `symUnwrap` the Master Key + identity private into an in-memory **`Vault`** (cleared on background/sign-out).
  **Done when:** a test unlocks (password + Secret Key → identity key in the vault).
- [ ] **4 — SwiftUI UI (adaptive, macOS/iOS/iPadOS).** Port the legacy login/unlock screens: instance URL (if
  not same-origin), email + password (login), Secret Key (unlock), the signed-in/unlocked states. One SwiftUI
  codebase, **adaptive** across iPhone / iPad / Mac (size classes — not a stretched phone layout). No tvOS
  head. **Done when:** `npm run check`-equivalent green (`swift build` + `swift test`), and the app round-trips
  login → unlock on macOS + an iOS simulator against a fresh instance.
- [ ] **5 — screenshots + docs.** Capture macOS (+ iOS/iPad) screenshots of the login + unlock screens →
  commit under `docs/design/screenshots/apple/`; record the `uc.account.{login,unlock}` **apple** surface in
  their coverage files; update the parity ledger. **Done when:** screenshots committed, coverage validator
  green, ledger reflects apple ✅.

## Definition of done

A universal Apple app (macOS + iOS + iPadOS, one SwiftUI target, adaptive layout) connects to a bootstrapped
instance, logs in via operator-blind challenge-response, and unlocks the in-memory vault — all crypto from
`VeyqCrypto`, all DTOs from the generated client, the password never sent, the vault never persisted. The
shared SPM libraries additionally build for **tvOS** (no tvOS UI head — deferred to passkey/QR handoff). macOS
+ iOS-simulator verified; screenshots committed as the Windows/Linux design reference. Parity ledger: apple ✅
(tvOS head pending), android/windows/linux pending. Keep this file as a record after merge.
