---
id: cr-0019
title: Apple — trust this device (DEVICE wrap, Keychain-backed) + login-fold unlock
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.trust-device, uc.account.set-device-security, uc.account.unlock]
adrs: [0005, 0003, 0010]
invariants: [operator-blind]
---

## Goal

Bring the web **trust-this-device** experience ([cr-0005](cr-0005-trust-device.md) +
[cr-0008](cr-0008-trusted-device-auto-unlock.md)) to the Apple client as the **first notch of a
per-device "device security level"** the user picks themselves. After a first password + Secret-Key
floor unlock, the app asks **how this device should unlock next time** — *with no default; the user must
choose* (until they do, the device stays at the full floor). This CR ships the **"only password"**
level: thereafter, on that device, signing in unlocks the vault with **only the password** — the Secret
Key goes back into the Emergency Kit. That level is a device-local **DEVICE `MasterKeyWrap`**:
`KEK = Argon2id(deviceFactor ‖ password)` where `deviceFactor` is a random, device-bound secret held
in the **Keychain** (`ThisDeviceOnly`, non-syncable) — the platform analog of the web's
non-extractable IndexedDB key (ADR 0005 §4: *Keychain key on apple*). On such a device the DEVICE
unlock **folds into login** (web cr-0008 parity): a successful sign-in unlocks immediately, with **no
second Secret-Key step**; if the device key or its server wrap is unusable, the client **falls back
automatically** to the Secret-Key floor.

**Threat-model framing (settled with the user).** The server is an *unknown, global* adversary — that
is *why* operator-blind is non-negotiable. The user's *own device* is a smaller, known-circle threat
(only "handed over / left unlocked"), so how much friction to trade for convenience there is **the
user's call**. The level slider relaxes **only local** defense-in-depth; **operator-blindness is
untouched at every level** — the server still sees only ciphertext wraps + an opaque token.

This CR is the **"only password"** level only (type the password each time). The two **passwordless**
levels — **"biometric"** (FaceID, a random Secure-Enclave key, no password at rest) and **"no login"**
(open straight to home, rides on the OS lock screen) — plus the **persistent session** both require and
the full three-notch **slider UI** are the next slice, **cr-0020** (see "Deferred / next slice" below).
cr-0019 deliberately needs **no session persistence** (the password login mints a fresh session each
time), which is the clean seam between the two CRs.

**Scope:** apple only — a Keychain-backed device-trust store, the `unlockWithDevice` / `trustThisDevice`
/ `untrustThisDevice` / `isTrusted` orchestration on `VeyqClient`, the login-fold in `AppModel`, a
"trust this device" affordance on the floor, and an untrust control once unlocked.
**No backend / contract / crypto change** — `POST`/`DELETE /api/account/wraps`, the DEVICE wrap in
`key-material`, the per-method cap + DEVICE-whitelisted delete, `V1.kekDevicePassword`, and the
`device_password_argon2id` vector all already exist (cr-0005; vector green on apple since cr-0014).

## Context & constraints

- Use-cases: [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md)
  (DEVICE wrap = device key + password; multi-device; untrust; floor always reachable; §"Routine unlock
  on a trusted device" — folds into login) + [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md).
- Decisions: [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md) (DEVICE backing =
  **Keychain key on apple**; method precedence; the Secret Key stays cold), [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)
  (auth ≠ unlock — login gates the session, the client opens the Master Key locally),
  [ADR 0010](../docs/decisions/0010-native-shared-contracts-not-code.md) (shared contracts, native
  per platform).
- **Web is the reference, not the source** (ADR 0010): port the *behaviour* of `trustDevice.ts` /
  `deviceKey.ts` / `unlock.ts`, not the code. The web data layer is IndexedDB + WebCrypto; here it is
  Keychain + `V1`.
- **Reuse the shipped Apple slices** (don't reinvent): `V1.kekDevicePassword` / `symWrap` / `symUnwrap`
  / `decodeKdfParams` / `encodeKdfParams` (VeyqCrypto), the `withRefresh` rotate-and-retry and
  `fetchKeyMaterial` (VeyqClient), the generated `AccountAPI.addAccountWrap` / `deleteAccountWrap`, the
  `Vault` / `Session` actors, the `pendingSecretKey` carry, and the floor UI components
  (`AuthCard` / `VeyqField` / `PrimaryButton` / `MessageBox`).
- **Invariants at risk:**
  - **operator-blind** — the server stores only the DEVICE **ciphertext** wrap + opaque metadata; the
    `deviceFactor`, the KEK, and the Master Key never leave the device. Add/remove act only on the
    **caller's own** wraps (ownership from the access token, never an id in a request body — already
    enforced backend-side).
  - **2SKD floor intact** — enrolment wraps the **already-unlocked** Master Key (read from the vault);
    the **Secret Key is never read or stored**. The floor PASSWORD wrap is unremovable (backend
    whitelists DEVICE on delete) and always reachable.

## Design decisions (settled)

- **`deviceFactor` = random Keychain secret (apple backing).** Generate 32 random bytes, store them in
  the Keychain as a generic-password item with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` and
  `kSecAttrSynchronizable = false` (device-bound, never iCloud-synced, only readable while the device is
  unlocked). `deviceFactor` is **fed straight into `V1.kekDevicePassword(deviceFactor, password, …)`** —
  the byte order matches the `device_password_argon2id` vector (factor-first), so the suite stays
  byte-identical without any new crypto. Unlike the web's *non-extractable* CryptoKey, a Keychain blob is
  readable by the app process — accepted for Stufe A because the password is still a required second
  factor (an exfiltrated `deviceFactor` alone opens nothing), and ADR 0005 §4 designates Keychain as the
  apple backing. **Secure-Enclave hardening** (a non-extractable SE key, `deviceFactor` via
  ECDH/HKDF) is folded into the **biometric CR**, where SE is the backing anyway.
- **One store, keyed per account.** A `DeviceTrustStore` persists `{ deviceFactor, wrapId }` for an
  account (Keychain service `me.raddatz.veyq.device-trust`, account = the login email). `isTrusted(email)`
  = an entry exists; `load` returns it; `save` replaces; `clear` removes. Behind a small protocol so unit
  tests use an in-memory double (Keychain on the simulator/CI host is entitlement-sensitive and
  cross-test-polluting; the real Keychain impl is exercised by the live round-trip in phase 4).
- **Level chooser after the first floor unlock; no default — the user must pick** (per the settled
  decision). The floor `unlock()` clears the password the moment it succeeds, so a later control would
  have to re-prompt. Instead, **right after a successful floor unlock**, the app presents the
  device-level choice while the just-typed password is still in hand (known-correct). In cr-0019 the
  offered choices are **"Secret Key each time"** (the current behaviour — store nothing) and **"Only
  password"** (enrol the DEVICE wrap); the passwordless notches are added in cr-0020. **Nothing is
  pre-selected** — the device stays at the floor until the user chooses. Build the chooser as a small
  ordered list of levels with the higher notches **disabled/"coming soon"** (or simply absent) so the
  cr-0020 slider is an extension, not a rewrite. Enrolling "Only password" reads the Master Key from the
  vault (a copy), generates `deviceFactor` + a random salt, derives `KEK = kekDevicePassword`,
  `symWrap`s the Master Key, `POST`s the DEVICE wrap, and stores `{deviceFactor, wrapId}` in the
  Keychain.
- **Re-trust replaces; multi-device; server is source of truth.** Each device enrols its **own** wrap
  (own factor, own salt). If the Keychain already holds a `wrapId`, enrol first `DELETE`s it, then
  `POST`s the new one (no duplicate accumulation). The per-account DEVICE-wrap **cap (409
  `too_many_devices`)** already exists backend-side.
- **Unlock by stored `wrapId`.** `unlockWithDevice(email, password)` reads `{deviceFactor, wrapId}` from
  the Keychain, fetches `key-material`, finds **its own** DEVICE wrap **by `wrapId`** (the authoritative
  match shipped in cr-0005 #6, not salt-guessing), decodes `kdfParams`, derives
  `kekDevicePassword(deviceFactor, password, salt, params)`, `symUnwrap`s the Master Key + identity, and
  opens the vault. Returns **false** (→ floor) when no Keychain entry / no matching wrap exists; throws
  generically on a wrong password (AEAD tag failure).
- **Login-fold (cr-0008 parity), no intermediate flash.** `AppModel.login(email, password)`: after a
  successful `client.login`, if `isTrusted(email)`, call `unlockWithDevice` **before** flipping the phase
  — on success go straight to `.home`; on failure (E1) drop the trust and fall through to the `.unlock`
  floor. The busy sign-in state stays up across the device unlock so it never flashes the floor as an
  intermediate step (the exact bug cr-0008 §1 fixed on web). An untrusted device is unchanged: login →
  floor.
- **Method precedence is additive, not a 2-way `if`** (ADR 0005 §7). The login-fold checks "is a DEVICE
  unlock available on this device" and otherwise uses the floor; structure it so PASSKEY / BIOMETRIC /
  RECOVERY slot in later without a rewrite.

## Security guardrails to mirror (from cr-0005 review)

- **Don't destroy the local device key on a missing wrap** (cr-0005 #2). `unlockWithDevice` finding no
  matching server wrap **falls back to the floor without clearing the Keychain** — a blind operator must
  not be able to withhold the wrap to force every trusted device to wipe its key. Explicit untrust /
  re-trust is the only path that clears it.
- **Enrol failure rolls back** (cr-0005 #3). If the Keychain `save` fails after the wrap `POST` succeeded,
  `DELETE` the just-created server wrap (no orphan) and surface a "couldn't trust this device" message;
  the unlock itself still stands.
- **Untrust is DEVICE-only + idempotent** (already enforced backend-side): `DELETE` removes only the
  caller's own DEVICE wrap; unknown id / another account / the floor is a silent 204.
- **By design (ADR 0005), not a defect:** a stolen *unlocked* device reduces to password strength —
  "wraps protect against key theft, not session misuse". Stufe A keeps the password a real second factor;
  the no-password-needed convenience is the deferred biometric CR.

## Phases

- [x] **1 — device-trust store (Keychain).** `Sources/VeyqKit/DeviceTrust.swift`: `TrustRecord {
  deviceFactor: Data, wrapId: String }` (Codable, base64 `deviceFactor`), a **synchronous** `DeviceTrustStore`
  protocol (`isTrusted(email:) -> Bool`, `load(email:) throws -> TrustRecord?`, `save(_:email:) throws`,
  `clear(email:) throws`), `KeychainDeviceTrustStore` (Security framework, `kSecClassGenericPassword`,
  `WhenUnlockedThisDeviceOnly`, `kSecAttrSynchronizable=false`, delete-then-add replace, JSON value), a
  top-level `makeDeviceFactor()` (`SecRandomCopyBytes`, 32 bytes), and `DeviceTrustError`. Test double
  `Tests/VeyqKitTests/InMemoryDeviceTrustStore.swift` (`final class` + `NSLock`, `@unchecked Sendable`, so
  it satisfies the sync protocol — the real Keychain is entitlement-sensitive from the SPM CLI, covered by
  the phase-4 live round-trip). **Done:** `swift build` green; `DeviceTrustStoreTests` 5/5 (save → isTrusted
  → load → clear round-trip, replace, per-account scoping, idempotent clear, 32-random-byte factor); full
  `swift test` 28/28 (1 env-gated skip), no regressions.
- [x] **2 — orchestration + unit tests.** `VeyqClient` injects `deviceTrust: any DeviceTrustStore`
  (default `KeychainDeviceTrustStore`) and adds `isTrusted(email:)`, `unlockWithDevice(email:password:)
  async throws -> Bool`, `trustThisDevice(email:password:) async throws` (enrol at unlock time from the
  in-memory Master Key; re-trust deletes the prior wrap first; **rollback-DELETE + `.trustFailed`** on a
  save failure; **`.tooManyDevices`** on 409), `untrustThisDevice(email:) async throws`, plus private
  `addDeviceWrap`/`deleteWrap` helpers (401 → `.sessionExpired` so `withRefresh` rotates-and-retries). New
  `VeyqError.trustFailed` / `.tooManyDevices`; `secureRandomBytes()` (salt+nonce) added to DeviceTrust.
  `AppModel`: the **login-fold** in `login()` (trusted → `unlockWithDevice` before flipping phase, busy
  stays up, E1 → floor; never flashes the floor), enrol after a successful floor `unlock(secretKey:,
  trustThisDevice:)` (enrol failure → `deviceTrustFailed`, the unlock still stands), an `untrust()` action,
  and `deviceTrusted`/`deviceTrustFailed` reset on `signOut`/`changeInstance`. **Done:** `swift test` 37/37
  (1 env-gated skip) — `TrustDeviceTests` 9/9 against a stateful stub that captures the POSTed DEVICE wrap
  and serves it back (real-crypto enrol → re-unlock by `wrapId`; wrong-password → `.unlockFailed`;
  not-trusted/ missing-wrap → false **without** clearing the store; re-trust replaces; untrust removes wrap
  + record; 409 → `.tooManyDevices`; save-failure rollback; 401 → refresh → unlock). macOS app target
  builds (`xcodebuild … BUILD SUCCEEDED`), so the `AppModel` fold/enrol/untrust wiring compiles; its
  composition is exercised end-to-end by the phase-4 live round-trip (AppModel is the App target, not the
  SPM test target — mirrors how cr-0008 left the web fold to the view).
- [x] **3 — UI (level chooser + untrust + login-fold UX).** New `App/DeviceSecurityChooser.swift`: the
  `DeviceUnlockLevel` enum (L0–L3) + a radio-style `DeviceSecurityChooser` (in the veyq look, `VeyqStyle`)
  — **no pre-selection**; **"Secret Key each time"** + **"Only my password"** active, **biometric / no
  login** rendered as disabled **"Coming soon"** rows so the cr-0020 slider extends it. `UnlockView` shows
  the chooser on the floor and **requires a level** (`canSubmit = !secretKey.isEmpty && level != nil` —
  honours "must choose"); submit passes `trustThisDevice: level == .onlyPassword` into
  `app.unlock(secretKey:trustThisDevice:)`. `HomeView`: a **"Stop trusting this device"** untrust control +
  a "trusted" note when `app.deviceTrusted`, and a "couldn't trust this device" `MessageBox` when
  `app.deviceTrustFailed`. The `AppModel` login-fold (phase 2) means a trusted device lands straight on
  home (floor never shown). **Done:** `xcodebuild` macOS **and** iOS-sim both `BUILD SUCCEEDED`; the
  lifecycle is driven live by phase 4.
- [x] **4 — live round-trip + screenshots + coverage/parity.** Two env-gated live vehicles against a
  fresh instance (MariaDB wiped → quarkusDev → `seed-owner.mts`): (a) **data-layer**
  `LiveRoundTripTests.testTrustDeviceRoundTripAgainstLiveInstance` (in-memory store, real wraps endpoints)
  — floor unlock → trust (POST) → fresh client unlocks **password-only** → wrong-password → untrust
  (DELETE) → unavailable; (b) **in-app UI** `RoundTripUITests.testTrustDeviceLifecycle` on iPhone 17 Pro
  (iOS 26.5) with the **real Keychain**, the full lifecycle: floor "Only my password" → trusted home →
  sign out → re-login **folds straight to home (no floor)** → untrust → re-login back to the floor. Both
  green; 7 design-ref screenshots written (`ios-trust-01..05` + refreshed `ios-03-unlock`/`ios-04-home`,
  README updated). Coverage YAML filled with apple unit+e2e refs (apple `gap` dropped); apple flipped to
  ✅ in [platform-parity.md](../docs/crosscutting/platform-parity.md). **Done:** hermetic `swift test`
  38/38 (2 env-gated skips); live `swift test --filter LiveRoundTripTests` 2/2; UI 2/2;
  `validate_coverage.py` green. *(macOS desktop captures still pending the Accessibility grant, as in
  cr-0015/0017 — iOS stands in.)* *(Minor cosmetic: the trusted-home note truncates to one line on the
  narrow iPhone width — functional, left for a polish pass.)*

## Definition of done

An Apple user who has unlocked once with password + Secret Key can **trust the device** and thereafter,
on that device, unlock with **only the password** — the unlock **folds into login** (no second step),
across fresh logins; can **untrust** (removes only that device's wrap + its Keychain entry, falls back to
the floor); and can trust **multiple** devices independently. The server holds only ciphertext wraps and
never the `deviceFactor`, KEK, Master Key, or Secret Key; the PASSWORD floor is unremovable and always
reachable by automatic fallback. A device whose Keychain entry or server wrap is gone falls back to the
floor **without** self-wiping its key. Coverage for `uc.account.trust-device` is green on apple and the
parity matrix reflects it (backend/http/web/**apple**). Biometric / passwordless unlock (a separate
`BIOMETRIC` wrap with a random Secure-Enclave key, no password at rest), the worker-cached re-lock, and
cross-device onboarding remain separate, planned work. After merge, keep this file as a record.

## Deferred / next slice (explicit)

- **cr-0020 — the two passwordless levels + the full slider (apple).** Settled with the user; use-cases
  written: [uc.account.biometric-unlock](../docs/features/account/use-cases/uc.account.biometric-unlock.md)
  (L2), [uc.account.auto-unlock](../docs/features/account/use-cases/uc.account.auto-unlock.md) (L3), the
  chooser [uc.account.set-device-security](../docs/features/account/use-cases/uc.account.set-device-security.md),
  and [ADR 0005 §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md). cr-0020 turns the
  cr-0019 chooser into the full **three-notch device-security slider** — *no login* / *only password* /
  *biometric* — and adds the two passwordless notches:
  - **"Biometric"** — a **separate wrap keyed by a random key K** (`symWrap(K, masterKey)`), K a
    **non-extractable Secure-Enclave key gated by FaceID/Touch** (ADR 0005 §4 "used directly — no
    password"). **Not** the account password stored biometrically: the password is also the **auth/login**
    factor (`deriveAuthKeypair`), so storing it — even biometric-gated — would let a biometric-passing
    local attacker authenticate **from anywhere**, not just open the local vault; a device-bound random
    key caps the blast radius to this device's vault.
  - **"No login"** — the *same* random-key wrap, but K (and the session token) held **ungated**
    (`WhenUnlockedThisDeviceOnly`). The app opens straight to home, riding on the **OS lock screen** as
    the only gate. An explicit, clearly-labelled choice — never a default — justified by the
    threat-model framing above (own-device = known-circle risk; the user owns that trade).
  - **Persistent session (the new machinery both passwordless levels need).** Without the password the
    client cannot mint a fresh session, so the **refresh token** must be persisted in the Keychain
    (FaceID-gated for biometric, ungated for no-login); token expiry/rotation falls back automatically to
    the password + Secret-Key floor. cr-0019 needs none of this (the password login mints a fresh session
    each time) — that is the seam between the two CRs.
  - Adds the new wrap method + a conformance vector + a small contract touch; SE backing also hardens the
    cr-0019 `deviceFactor` if revisited there.
  - **Chooser UI:** cr-0019 ships the **radio list** (clearest for 2 active + 2 disabled notches, and it
    encodes "no selection yet" naturally). For the full 4-level slider, the explored direction is a
    **vertical stepped scale** (most-private top → most-convenient bottom, labelled stops) rather than a
    literal iOS `Slider` (horizontal-only; can't represent "no default"; weak per-stop labels). Decide in
    cr-0020 when all four levels are live.
- **Android twin** of both slices (the cr-0018 successor) and **android/windows/linux** trust — separate
  per-surface CRs.
