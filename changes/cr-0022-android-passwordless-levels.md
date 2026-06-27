---
id: cr-0022
title: Android — biometric (L2) + no-login (L3) unlock; persistent session; activate the passwordless levels
status: planned          # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.biometric-unlock, uc.account.auto-unlock, uc.account.set-device-security]
adrs: [0005, 0003, 0010]
invariants: [operator-blind]
---

## Goal

The Android twin of [cr-0020](cr-0020-apple-passwordless-levels.md) (Apple L2/L3): add the two
**passwordless** unlock levels on top of cr-0021's L0/L1, completing the per-device slider
([ADR 0005 §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md)):

- **L2 — Biometric**: open with **only a BiometricPrompt** (fingerprint / face), no password. A device-held
  **random key K** wraps the Master Key (`symWrap(K, masterKey)`, stored method=DEVICE — no contract
  change); K is protected by a **hardware Android Keystore key with `setUserAuthenticationRequired(true)`**
  (StrongBox where available), released only after a `BIOMETRIC_STRONG` auth via `BiometricPrompt` +
  `CryptoObject`. Unlike Apple's gated-Keychain compromise, this is the **proper hardware-gated** key — and
  it is **emulator-testable** (`adb emu finger touch`).
- **L3 — No login**: open **straight to home**, no prompt. The **same** random-K wrap, but K held **ungated**
  (`EncryptedSharedPreferences`) — gated only by the OS lock screen. Explicit opt-in, never a default.

Both passwordless levels need a **persisted session** (the refresh token) so the app resumes without the
password; app-launch **auto-restore** opens the vault (L3 silently, L2 after a biometric). Activates the
passwordless rows in the cr-0021 chooser.

## Context & constraints

- Use-cases: biometric-unlock (L2), auto-unlock (L3), set-device-security — refined; coverage already
  android-mapped for L1 (cr-0021). ADR 0005 §4 (biometric = Keystore key gated by BiometricPrompt, used
  directly), §8–13 (levels), ADR 0003 (auth ≠ unlock → passwordless needs a persisted session), ADR 0010.
- **Reuse cr-0021 + `:crypto`:** `symWrap`/`symUnwrap`, `withRefresh`, the generated `AccountWrapsApi`, the
  Compose chooser (flip L2/L3 active), the login-fold scaffold, the `DeviceTrustStore` (generalised below).
- **No backend / contract / vector change:** the K-wrap is stored as **method=DEVICE** (the server can't and
  needn't tell L1 from L2 from L3); K is the KEK directly (no Argon2id), already covered by the `sym_*`
  vectors.
- **Invariants:** operator-blind at every level (server holds only ciphertext wraps + an opaque token; never
  K, the Master Key, the password). L3 relaxes **only local** device defense-in-depth (rides the OS lock),
  an explicit user choice — not server trust.

## Design decisions (settled)

- **Architecture split — `:app` owns the biometric, `:client` stays pure JVM.** `BiometricPrompt` is
  Activity-bound + async and `:client` has no Android SDK, so the **K material + biometric live in `:app`**.
  `:client` gains **`unlockWithKey(email, k: ByteArray): Boolean`** (fetch key-material → find the DEVICE
  wrap by the stored `wrapId` → `symUnwrap(k, masterKey)` → open the vault) and **`resumeSession(email)`**
  (read the persisted refresh token → `refresh()`); `:app` generates K, runs `BiometricPrompt` +
  `CryptoObject` to decrypt K (L2) or reads it ungated (L3), then calls `unlockWithKey`. `:client` never
  sees the biometric flow.
- **L2 key gating = hardware Keystore + BiometricPrompt.** A Keystore **AES-GCM** key,
  `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`,
  StrongBox where available, **encrypts K**; the K-ciphertext (+ IV) is stored. Decrypt = `BiometricPrompt
  .authenticate(CryptoObject(cipher))` → on success the `Cipher` decrypts K. Enrol-time encryption of K
  needs no auth (encrypt is allowed; only decrypt is gated). L3 stores K in `EncryptedSharedPreferences`
  (ungated).
- **Persisted session (rotation-tracked).** The **refresh token lives ungated** (so rotation can be written
  back prompt-free) in the store; `refresh()` persists the rotated token for an L2/L3 device. Security: an
  ungated session token alone fetches only ciphertext (operator-blind); the *vault* key is the gated secret.
  (Mirrors cr-0020.)
- **Storage — generalise `DeviceTrustStore` → `DeviceSecurityStore` (4 levels).** `:client` interface
  carries `currentLevel`, `wrapId`, `refreshToken` (what the orchestration needs); the `:app`
  `KeystoreDeviceSecurityStore` adds the K storage (gated/ungated) + the Keystore HMAC `deviceFactor` (L1).
  cr-0021's L1 stays green through the generalised store.
- **App-launch auto-restore.** On launch, read the level (ungated): **L3** → read K + token → resume + open
  → home (silent); **L2** → `BiometricPrompt` → decrypt K → resume + open → home; **L0/L1** → normal connect
  / sign-in (L1 still folds at login). Token expired / key gone → fall back to the floor.
- **Chooser: activate L2/L3** (keep the radio list). `DeviceUnlockLevel.available` → L2 offered only when
  `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == SUCCESS` (else a "Not available" row); L3 explicit +
  a device-credential check. Mutually exclusive per device; switching swaps the one wrap.

## Phases

- [x] **1 — storage generalisation (`DeviceSecurityStore`).** `:client` `DeviceTrustStore` →
  `DeviceSecurityStore` + `DeviceLevel` (ONLY_PASSWORD/BIOMETRIC/NO_LOGIN; L0 = null): accessors
  `currentLevel`/`wrapId`/`refreshToken`/`updateRefreshToken`, L1 `beginEnrol`/`deviceFactor`/
  `commitOnlyPassword`, and `commitPasswordless(level, wrapId, refreshToken)` for L2/L3 (K is `:app`-side).
  `VeyqClient` L1 migrated (`isTrusted` = `currentLevel == ONLY_PASSWORD`; unlock/trust/untrust use the new
  accessors); `DisabledDeviceSecurityStore` default. `:app` `KeystoreDeviceTrustStore` →
  `KeystoreDeviceSecurityStore` (level/wrapId/token in SharedPreferences; the L1 Keystore HMAC kept; the
  L2/L3 gated-K storage lands in phase 3). Test double + tests renamed. **Done:** `:client:test`
  `DeviceSecurityStoreTest` 6/6 + `TrustDeviceTest` 9/9 (37 total, 1 live skip); `:app:assembleDebug`
  `BUILD SUCCESSFUL`.
- [ ] **2 — orchestration + unit tests.** `:client` `VeyqClient`: `unlockWithKey(email, k)`,
  `resumeSession(email)` (+ rotation persist-back), `setPasswordlessWrap(email, masterKey, k): wrapId`,
  `currentLevel`. **Check:** `:client:test` — enrol a K-wrap → resume + unlockWithKey round-trip (in-memory
  store + MockWebServer); rotation persists; missing wrap → false; dead token → false.
- [ ] **3 — `:app` biometric + auto-restore + chooser.** `:app`: BiometricPrompt + CryptoObject K
  decryption, the enrol wiring (L2/L3 from the floor chooser), app-launch auto-restore (L3 silent / L2
  biometric), chooser L2/L3 activation (BiometricManager gate), untrust/level-switch. **Check:**
  `:app:assembleDebug`.
- [ ] **4 — live + emulator round-trip + screenshots + coverage/parity.** Emulator UI round-trips with
  **simulated fingerprint** (`adb emu finger touch <id>`): enrol L2 → relaunch → fingerprint → home; enrol
  L3 → relaunch → straight to home. Live data-layer wrap/resume round-trip. Fill `biometric-unlock` +
  `auto-unlock` coverage (android refs) + `set-device-security` (android L2/L3); flip android ✅ for L2/L3 in
  [platform-parity.md](../docs/crosscutting/platform-parity.md). **Check:** `:client:test` + emulator UI
  green; `validate_coverage.py` green.

## Definition of done

On Android, a user can set a device to **Biometric** (open with only a fingerprint/face) or **No login**
(open straight to home), in addition to cr-0021's L0/L1, from the chooser (L2 hidden without biometric
hardware; L3 explicit). Both resume the session and open the vault on launch (L2 after a biometric, L3
silently), falling back to the floor when the key/token is unusable. Operator-blindness holds at every
level. Coverage for `uc.account.biometric-unlock` + `uc.account.auto-unlock` is green on android and parity
reflects it — completing the **web·apple·android** picture for the device unlock levels. Keep this file as a
record.

## Deferred

- **Windows / Linux** device-security levels — per-surface CRs (TPM + Windows Hello / Secret Service).
- The **vertical-scale** chooser UI (kept the radio list, as on apple).
