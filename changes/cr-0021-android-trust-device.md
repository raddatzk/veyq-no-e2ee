---
id: cr-0021
title: Android — trust this device (DEVICE wrap, Keystore-HMAC) + login-fold unlock
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.trust-device, uc.account.set-device-security, uc.account.unlock]
adrs: ["0005", "0003", "0010"]
invariants: [operator-blind]
---

## Goal

The Android twin of [cr-0019](cr-0019-apple-trust-device.md) (Apple L1): after a first password + Secret-Key
floor unlock, a user can set this device to **"only password"** — thereafter signing in unlocks with **only
the password** (the unlock folds into login), the Secret Key going back into the Emergency Kit. The level is
a device-local **DEVICE `MasterKeyWrap`**: `KEK = Argon2id(deviceFactor + password)`, where on Android the
`deviceFactor` comes from a **non-extractable HMAC key in the Android Keystore** (`KEY_ALGORITHM_HMAC_SHA256`,
StrongBox where available) signing a fixed constant — the **web-parity** backing (its bytes never leave the
Keystore; exfiltration-resistant), stronger than Apple's Keychain blob.

This is **L1 only** — the first notch of the per-device unlock-level chooser (no default; the user must
choose). The passwordless levels **L2 (biometric)** and **L3 (no login)** are the next slice, **cr-0022**.

## Context & constraints

- Use-cases: [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md) (L1 —
  DEVICE wrap), [set-device-security](../docs/features/account/use-cases/uc.account.set-device-security.md)
  (the chooser, L0/L1 active here), [unlock](../docs/features/account/use-cases/uc.account.unlock.md) (the
  always-reachable floor).
- Decisions: [ADR 0005 §4, §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md) (DEVICE backing
  = **Keystore key on android**; device unlock levels; no default; floor always reachable), ADR 0003 (auth ≠
  unlock), ADR 0010 (shared contracts, native per platform).
- **Apple cr-0019 is the behavioural reference** (not the source): port the *flow*; the Android data layer is
  the contracts-generated client + `:crypto` V1 + Android Keystore.
- **Reuse the shipped Android slices** (don't reinvent): `:crypto` `V1.kekDevicePassword` / `symWrap` /
  `symUnwrap` / `encodeKdfParams` / `decodeKdfParams` (+ the `device_password_argon2id` vector, already
  green on `:crypto:test`), the `:client` `withRefresh` / `fetchKeyMaterial` / `Session` / `Vault`, the
  generated `AccountWrapsApi.addAccountWrap` / `deleteAccountWrap` + `AccountKeyMaterial`, and the `:app`
  Compose components (`AuthCard`/`VeyqField`/`PrimaryButton`) + `AppModel` phase machine.
- **Module split (ADR 0010):** `:client` is **pure JVM** (no Android SDK), so the Keystore-backed
  `deviceFactor` cannot live there. The **`DeviceTrustStore` interface + `TrustRecord`** go in `:client`
  (the orchestration depends on the interface); the **`KeystoreDeviceTrustStore`** impl (Android Keystore
  HMAC + SharedPreferences for the wrap id) goes in `:app`; `:client` tests inject an in-memory double.
- **Invariants at risk:** operator-blind — the server stores only the DEVICE ciphertext wrap + opaque
  metadata; the Keystore HMAC key, the `deviceFactor`, the KEK, the Master Key never leave the device; the
  add/remove endpoints act only on the caller's own wraps. 2SKD floor intact — enrolment wraps the
  already-unlocked Master Key; the Secret Key is never read or stored; the PASSWORD floor is unremovable
  (backend whitelists DEVICE on delete).

## Design decisions (settled with the user)

- **`deviceFactor` = non-extractable Keystore HMAC (web parity).** A per-account `KEY_ALGORITHM_HMAC_SHA256`
  key in the `AndroidKeyStore` (`setIsStrongBoxBacked(true)` where the device supports it, graceful
  fallback otherwise), created non-extractable; `deviceFactor = Mac("HmacSHA256", keystoreKey).doFinal(utf8
  ("veyq/v1/device-bind"))`. The key bytes never exist in the app heap — exactly the web's non-extractable
  `CryptoKey` model. Fed factor-first into `V1.kekDevicePassword`, matching the `device_password_argon2id`
  vector.
- **Store split + shape.** `:client` `interface DeviceTrustStore { isTrusted(email); load(email):
  TrustRecord?; beginEnrol(email): ByteArray /*ensure key → deviceFactor*/; commitWrapId(email, wrapId);
  clear(email) }` with `TrustRecord(deviceFactor, wrapId)`. The Keystore impl derives `deviceFactor` from
  the Keystore key (deterministic per device) and persists only the `wrapId` (SharedPreferences). Re-trust
  reuses the device-bound key (the `deviceFactor` is stable) and just mints a **new salt + wrap** (deleting
  the prior one). The in-memory double returns a fixed `deviceFactor`.
- **Level chooser after the first floor unlock; no default — the user must pick** (mirrors cr-0019). On the
  floor, an ordered list with **nothing pre-selected**: **"Secret Key each time"** + **"Only my password"**
  active; the passwordless rows shown disabled (cr-0022) so the chooser extends, not rewrites. Enrol runs
  right after a successful floor unlock, reusing the just-typed (known-correct) password.
- **Login-fold (cr-0008 parity).** `AppModel.login`: after a successful login, if the device is trusted,
  `unlockWithDevice(email, password)` runs **before** flipping the phase — straight to home on success, the
  floor on E1; the busy sign-in state stays up so the floor never flashes.
- **Security guardrails (mirror cr-0019/cr-0005 review):** `unlockWithDevice` finding no matching server
  wrap **falls back to the floor without clearing the local key** (no blind-operator self-wipe); enrol
  failure **rolls back** the just-created server wrap; untrust deletes only the caller's DEVICE wrap
  (idempotent, backend-whitelisted); a stolen *unlocked* device reduces to password strength (by design).

## Phases

- [x] **1 — device-trust store (interface + Keystore impl + double).** `:client`
  [`DeviceTrustStore`](../android/client/src/main/kotlin/com/veyq/client/DeviceTrustStore.kt) interface +
  `TrustRecord` (deviceFactor + wrapId; two-step enrol `beginEnrol`→`commitWrapId` because the factor is
  *derived*, not handed in). `:app`
  [`KeystoreDeviceTrustStore`](../android/app/src/main/kotlin/com/veyq/app/security/KeystoreDeviceTrustStore.kt)
  (non-extractable `AndroidKeyStore` HMAC-SHA256, StrongBox-with-fallback, `deviceFactor = Mac(key,
  "veyq/v1/device-bind")`, `wrapId` in private SharedPreferences). `:client` test double
  `InMemoryDeviceTrustStore`. **Done:** `:client:test` `DeviceTrustStoreTest` **5/5**
  (enrol→commit→load→clear, stable factor, replace, per-account scoping, idempotent clear);
  `:app:assembleDebug` `BUILD SUCCESSFUL` (the Keystore impl compiles).
- [x] **2 — orchestration + unit tests.** `VeyqClient` injects `deviceTrust: DeviceTrustStore`
  (default `DisabledDeviceTrustStore` — never-trusted no-op for the headless/login-only path) + an
  `AccountWrapsApi`; adds `isTrusted`, `unlockWithDevice(email, password): Boolean`, `trustThisDevice`
  (`beginEnrol` → `kekDevicePassword` → `symWrap` → `addAccountWrap` → `commitWrapId`; re-trust deletes the
  prior wrap first; **rollback-DELETE + `TrustFailed`** on commit failure; **`TooManyDevices`** on 409),
  `untrustThisDevice`, + private `addDeviceWrap`/`deleteWrap` (401 → `SessionExpired` so `withRefresh`
  retries). New `VeyqError.TrustFailed`/`TooManyDevices`; `SecureRandom` salt+nonce. **Done:**
  `:client:test` `TrustDeviceTest` **9/9** against a stateful `MockWebServer` dispatcher that captures the
  POSTed DEVICE wrap and serves it back (real-crypto enrol → re-unlock by `wrapId`; wrong-password →
  `UnlockFailed`; not-trusted/ missing-wrap → false **without** clearing the store; re-trust replaces;
  untrust; 409 → `TooManyDevices`; commit rollback; 401 → refresh → unlock). Full `:client:test` +
  `:crypto:test` 36 (1 live skip), 0 failures.
- [x] **3 — UI (chooser + untrust + login-fold).** New `components/DeviceSecurityChooser.kt`
  (`DeviceUnlockLevel` L0–L3 + a radio-style Compose chooser — no pre-selection; L0/L1 active, passwordless
  rows disabled "Coming soon"). `UnlockScreen` shows it and **requires a level** (`enabled = secretKey
  .isNotEmpty() && level != null`); submit passes the chosen level into `app.unlock(secretKey, level)`.
  `AppModel` → **`AndroidViewModel`** holding a `KeystoreDeviceTrustStore(application)` injected into the
  `VeyqClient`; the **login-fold** in `login()` (trusted → `unlockWithDevice` before flipping the phase),
  enrol after a floor unlock (L1 → `trustThisDevice`; enrol failure → `deviceTrustFailed`), `untrust()`, and
  `deviceTrusted`/`deviceTrustFailed` reset on sign-out / change-instance. `HomeScreen`: a **"Stop trusting
  this device"** control + a trusted note + the enrol-failure message. **Done:** `:app:assembleDebug`
  `BUILD SUCCESSFUL`.
- [x] **4 — live round-trip + screenshots + coverage/parity.** In-app **emulator** UI round-trip
  (`RoundTripUiTest.trustDeviceLifecycle`, Pixel_10 / real Android Keystore) through the full lifecycle:
  floor "Only my password" → trusted home → sign out → re-login **folds straight to home (no floor)** →
  untrust → re-login back to the floor — green against a fresh live instance; 4 trust screenshots +
  refreshed `android-03-unlock`/`-04-home` (README updated). The existing `connectSignInUnlock` updated for
  the chooser (`performScrollTo` — the chooser pushes Unlock below the fold). Coverage filled with android
  unit+e2e refs for `trust-device` (android gap dropped) + `set-device-security` (android); android flipped
  ✅ for both in [platform-parity.md](../docs/crosscutting/platform-parity.md) — `trust-device` is now
  **web·apple·android**. **Done:** `:client:test` 36 (1 skip); the emulator UI round-trip 2/2;
  `validate_coverage.py` green.

## Definition of done

An Android user who has unlocked once with password + Secret Key can set this device to **"only password"**
and thereafter unlock with **only the password** — folding into login, across fresh logins — backed by a
**non-extractable Keystore HMAC** `deviceFactor`; can **untrust** (removing only that device's wrap + key,
falling back to the floor); and the floor stays unremovable + always reachable. The server holds only
ciphertext wraps and never the Keystore key, `deviceFactor`, KEK, Master Key, or Secret Key. Coverage for
`uc.account.trust-device` is green on android and parity reflects it (backend/http/web/apple/**android**).
The passwordless levels (**cr-0022**), full content, and the other platforms remain separate work. Keep this
file as a record.

## Deferred / next slice

- **cr-0022 — Android L2 (biometric) + L3 (no login)**, the passwordless twin of [cr-0020](cr-0020-apple-passwordless-levels.md):
  a random-K wrap (method=DEVICE), **BiometricPrompt + a `setUserAuthenticationRequired` Keystore key** for
  L2 (hardware-backed **and** emulator-testable via `adb emu finger touch`, so unlike Apple no weaker
  fallback is needed), a persisted session (EncryptedSharedPreferences / Keystore), and app-launch
  auto-restore. Activates the passwordless rows in the chooser.
