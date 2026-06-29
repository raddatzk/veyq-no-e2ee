---
id: cr-0016
title: Android client — login + unlock (Kotlin / Jetpack Compose)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.login, uc.account.unlock]   # the Android surface (web ✅, apple ✅ via cr-0015)
adrs: ["0003", "0004", "0005", "0009", "0010"]
invariants: [operator-blind]
---

## Goal

The native **Android client surface**: a Jetpack Compose app that connects to an **already-bootstrapped**
instance, **logs in** (operator-blind challenge-response, ADR 0003) and **unlocks** the vault (key-material
→ unlock KEK → unwrap Master Key + identity private, ADR 0005), reusing the cr-0014 `:crypto` (`V1.kt`) and
the `contracts/`. This is the Android twin of cr-0015 (Apple) — same flow, same data-layer shape, the same
behavioural contract (`http/` acceptance + use-cases), implemented in the platform-idiomatic stack.

**Scope:** login + unlock only (connect → sign-in → home), on the phone/tablet Compose head.
**Deferred:** trust-device, passkey, recovery, sessions, content/Memory, ML-KEM (lands with the content slice).

## Context & constraints

- **Module split** (mirrors Apple's `VeyqCrypto`/`VeyqKit`/App, and keeps a UI-less core for the future JVM
  agent, ADR 0010):
  - `:crypto` — exists (cr-0014), pure Kotlin/JVM `v1` suite.
  - `:client` — **new, pure Kotlin/JVM**: the generated OpenAPI client + the data layer (session, vault,
    login/unlock orchestration). Pure JVM so it tests via `:client:test` **without** the Android SDK and is
    reusable by a headless JVM agent.
  - `:app` — **new, Android (`com.android.application`) + Compose**: a thin UI head over `:client`.
- **API-first:** the Kotlin client is **generated** from `contracts/api/{setup,auth,account}.openapi.yaml`
  (never hand-written DTOs). Generator decided in phase 1 — **`org.openapi.generator` Gradle plugin**, kotlin
  client, `jvm-okhttp4` + kotlinx-serialization (all pure JVM) is the default. The three specs have colliding
  component names (`MasterKeyWrap`, `ErrorResponse`), so each generates into its **own package**
  (`com.veyq.api.{setup,auth,account}`), exactly as Apple uses three modules.
- **Reuse `:crypto`** (cr-0014): `deriveAuthSalt` / `deriveAuthKeypair` / `authSign` (login),
  `kekPasswordSecret` + `symUnwrap` (unlock). No new crypto. ML-KEM still deferred.
- **Reuse legacy UI/UX — never invent** (ADR 0010 / `android/CLAUDE.md`): orient on `legacy/android`
  (read-only) and the **Apple/web design already shipped** (the `VeyqStyle` tokens + copy from cr-0015) so
  the Android head matches the one veyq look; port screens, swap the data layer to the generated client +
  `:crypto`. **Governed by** the cross-platform [`ui-design-parity.md`](../docs/crosscutting/ui-design-parity.md)
  (canonical role tokens light+dark, shared component contract, size-class responsive rule) and its Android
  mapping [`ui-android-styleguide.md`](../docs/crosscutting/ui-android-styleguide.md): Material 3 themed to
  the veyq tokens (not stock Material), `WindowSizeClass` responsive (phone fills, tablet caps+centres),
  same `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox` component names as Apple.
- **Operator-blind:** the password is never sent (challenge-response); the vault (Master Key + identity
  priv) is **in-memory only**, cleared on background/sign-out — the Android analog of the web `vault.ts` /
  Apple `Vault`.
- **Dev-environment reality (good news vs the desktop heads):** the Android SDK is installed
  (`~/Library/Android/sdk`: platform-tools, emulator, system-image android-37, an existing `Pixel_10` AVD,
  platforms 36/36.1). So the Compose head **builds AND runs here**, and the live UI round-trip + screenshots
  are captured on the emulator via **adb screencap** — no TCC/Accessibility wall like macOS. `:crypto`/
  `:client` verify without the SDK; `:app` needs it.
- **Parity ledger:** web ✅ · apple ✅ (cr-0015) · **android = this CR** · windows / linux = pending.

## Phases

- [x] **1 — client + scaffold.** Add the `org.openapi.generator` plugin; generate `:client`'s setup/auth/
  account Kotlin clients (separate packages) from the symlinked/contract specs; a thin facade
  (`VeyqApi`-equivalent) over an OkHttp client + base URL; `:client` as a pure-JVM Gradle module; `:app` as
  an Android/Compose module (`com.android.application`, Compose BOM, min/target SDK, the `Pixel_10`-class
  device). `settings.gradle.kts` includes `:client` + `:app`. **Done when:** `:crypto:test` still green,
  `:client` compiles + a trivial test passes, `:app:assembleDebug` builds (AGP + SDK).
  **DONE.** Toolchain notes: AGP **9.2.1** + Gradle 9.5.1 force **built-in Kotlin (KGP 2.2.10)**; all
  modules unified to KGP 2.2.10 (down from 2.3.21 — `:crypto:test` still green on JDK 25). Every plugin is
  declared once in the root `build.gradle.kts` (`apply false`) so AGP + KGP share one classloader (else
  `KotlinAndroidTarget` → removed `BaseVariant` NoClassDefFound). The generator's `x-field-extra-annotation`
  (Quarkus bean-validation) is stripped into a sanitized spec copy so the client carries no `jakarta`. The
  okhttp4 client is suspend-internally but exposes blocking convenience methods → blocking orchestration.
  compileSdk pinned to **36** (android-37 platform not yet on the CLI channel) with the late-2025 36-wave
  AndroidX.
- [x] **2 — login (data layer).** `:client` login mirrors web/Apple: `POST /api/login/challenge` (carries
  the instanceSalt) → `deriveAuthSalt` → `deriveAuthKeypair` → `authSign(nonce)` → `POST /api/login/verify`
  → store opaque access+refresh in an in-memory `Session`; `refresh()` (drops tokens on rejection),
  `logout()`, a `withRefresh` rotate-on-401 helper (ADR 0004), and a bearer interceptor. **Done when:** a
  `:client:test` (MockWebServer) signs in and asserts the signature it sent **verifies** against the
  independently-derived keypair (operator-blind), plus refresh-rotation / no-session / withRefresh cases.
  **DONE** — `LoginTest` 4/4 green (`VeyqClient`, `Session`, `BearerInterceptor`, `VeyqError`).
- [x] **3 — unlock (data layer).** `GET /api/account/key-material` (via `withRefresh`) → pick the PASSWORD
  wrap → `decodeKdfParams` → `kekPasswordSecret(secretKey, password)` → `symUnwrap` Master Key (`master-key`)
  → `symUnwrap` identity private (`identity-priv`) into an in-memory **`Vault`** (cleared on background/
  sign-out; identity held as raw bytes — ML-KEM parsing deferred). Errors mirror Apple (`unlockUnavailable`
  / `invalidSecretKey` / `unlockFailed`). **Done when:** a `:client:test` builds key material with the same
  `V1` primitives and asserts unlock recovers the **exact** Master Key + identity bytes; wrong-Secret-Key,
  no-PASSWORD-wrap, key-material-401→refresh→retry cases.
  **DONE** — `UnlockTest` 5/5 green (`Vault` zero-on-clear, copy-on-read).
- [x] **4 — Compose UI (connect → sign-in → home).** Port the cr-0015 screens to Compose: instance URL
  (Connect + `setupRequired()` gate), email + password + Secret Key (Sign in → `login` then `unlock`, secrets
  dropped after submit), the unlocked Home (+ sign-out). One Compose tree, adaptive (phone/tablet), matching
  the shipped veyq design tokens/copy. **Done when:** `:app:assembleDebug` green and the app round-trips
  login → unlock on the emulator against a live instance.
  **DONE** — `VeyqTheme` (parity role tokens light+dark) + `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox`
  composables + `AppModel`/`RootScreen` + Connect/SignIn/Home screens; edge-to-edge, width-capped card.
  `:app:assembleDebug` green; live emulator round-trip verified in phase 5.
- [x] **5 — live round-trip + screenshots + docs.** Prove it end-to-end against a real instance (MariaDB +
  `quarkusDev`, owner seeded via `web/scripts/seed-owner.mts`): an env-gated `:client` live round-trip
  (OkHttp) **and** an instrumented Compose UI test (`:app:connectedDebugAndroidTest` on the `Pixel_10`
  emulator) driving connect → sign-in → unlock. Capture screenshots via **adb screencap** → commit under
  `docs/design/screenshots/android/`; record the `uc.account.{login,unlock}` **android** surface in the
  coverage files; update the parity ledger. **Done when:** round-trip green, screenshots committed,
  `validate_coverage.py` green, ledger reflects android ✅.
  **DONE** — `LiveRoundTripTest` (data layer, OkHttp) + `RoundTripUiTest` (full Compose UI) both green
  against a live instance on the API-37 emulator; `kevin@veyq.test` confirmed on the unlocked Home.
  Screenshots committed (`android-01-connect` / `02-signin` / `03-home`) via the AndroidX **TestStorage**
  service (AGP pulls them; adb screencap would be wiped by the post-run uninstall) with a reproducer README.
  Coverage files record the android surface; `validate_coverage.py` green; platform-parity ledger = android ✅.
  Test-lib note: espresso bumped to 3.7.0 — older reflects on `InputManager.getInstance()`, removed in API 37.

## Definition of done

A Kotlin/Compose Android app connects to a bootstrapped instance, logs in via operator-blind challenge-
response, and unlocks the in-memory vault — all crypto from `:crypto`, all DTOs from the generated client,
the password never sent, the vault never persisted. `:crypto`/`:client` verify without the SDK; `:app` builds
+ round-trips on the emulator. Live round-trip green (data layer + Compose UI); screenshots committed; android
surface recorded in coverage. Parity ledger: web + apple + android ✅; windows/linux pending. Keep this file
as a record after merge.
