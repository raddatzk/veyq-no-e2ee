---
id: cr-0020
title: Apple — biometric (L2) + no-login (L3) unlock; persistent session; activate the passwordless levels
status: planned          # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.biometric-unlock, uc.account.auto-unlock, uc.account.set-device-security]
adrs: [0005, 0003, 0010]
invariants: [operator-blind]
---

## Goal

Add the two **passwordless** device unlock levels to the Apple client, completing the per-device security
slider ([ADR 0005 §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md)) on top of cr-0019's
L0/L1:

- **L2 — Biometric** ([uc.account.biometric-unlock](../docs/features/account/use-cases/uc.account.biometric-unlock.md)):
  open with **only a live Face ID / Touch ID**, no password. A device-held **random key K** wraps the
  Master Key (`symWrap(K, masterKey, "master-key")`); K is held in a **biometric-gated Keychain item**
  (`.biometryCurrentSet`) — the gate is Secure-Enclave-enforced, the bytes return to the app only after a
  matching biometric.
- **L3 — No login** ([uc.account.auto-unlock](../docs/features/account/use-cases/uc.account.auto-unlock.md)):
  open **straight to home**, no prompt. The **same** random-K wrap, but K is held **ungated**
  (`WhenUnlockedThisDeviceOnly`) — gated only by the OS lock screen. Explicit opt-in, never a default,
  requires a device passcode.

Both passwordless levels need a **persisted session** ([ADR 0005 §11](../docs/decisions/0005-unlock-methods-and-device-trust.md)):
the refresh token is stored in the Keychain (gated for L2, ungated for L3) so the app can resume without
the password (which it no longer asks for); token expiry falls back to the floor. cr-0019's L0/L1 persist
no session. This CR also wires **app-launch auto-restore** and **activates L2/L3** in the existing chooser.

## Context & constraints

- Use-cases: biometric-unlock (L2), auto-unlock (L3), set-device-security (the chooser/transitions) — all
  refined, `status: planned` coverage written in cr-0019.
- Decisions ([ADR 0005 §4, §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md)): convenience
  wraps device-local, backed per platform; levels mutually exclusive per device; no default; floor always
  reachable; operator-blind at every level; passwordless ⇒ persisted session.
- **Reuse cr-0019:** the `symWrap`/`symUnwrap` machinery, the `withRefresh` rotate-and-retry, the generated
  `addAccountWrap`/`deleteAccountWrap`, the `DeviceSecurityChooser` (just flip L2/L3 `available`), the
  login-fold scaffold, and the Keychain plumbing (generalised below).
- **No backend / contract / crypto-vector change.** L2/L3 use a **random device-held key K** + the existing
  `symWrap` (already vector-covered by `sym_master_key`); K is platform glue (device-local), so — unlike a
  password-derived KEK — it needs **no new conformance vector**. The server still stores one ciphertext
  wrap per the existing wraps endpoints and cannot tell L1 from L2 from L3.
- **Invariants at risk:** operator-blind holds at every level (server sees only the ciphertext wrap + an
  opaque refresh token; never K, the Master Key, or the password). L3 relaxes **only local** device
  defense-in-depth (rides the OS lock), an explicit, warned user choice — not server trust.

## Design decisions (settled with the user)

- **L2 backing = biometric-gated Keychain, not full Secure-Enclave/ECIES** (settled). K lives in a Keychain
  item created with `SecAccessControlCreateWithFlags(.biometryCurrentSet)` — reading it triggers Face ID /
  Touch ID and the **Secure Enclave enforces the gate**; the bytes then return to the app process. Chosen
  over a non-extractable SE/ECIES key because it is **automatable in the iOS Simulator** (which has no
  Secure Enclave) and is ADR-0005-§4-consistent ("a key gated by FaceID"). True non-extractable SE/ECIES
  (key never leaves the enclave) is recorded as a **future hardening**, not this slice.
- **L2 and L3 share one wrap; only the gating differs.** Both: `K = 32 random bytes`,
  `wrap = symWrap(K, masterKey, "master-key")`, server-stored via `POST /api/account/wraps`. The **only**
  difference is the Keychain access-control on K **and** on the persisted refresh token — `.biometryCurrentSet`
  for L2, `WhenUnlockedThisDeviceOnly` (ungated) for L3. So a level switch L2⇄L3 is a re-store under a
  different gate (+ a fresh wrap), never new crypto.

  > **Wrap-method note (no contract change).** The K-wrap is stored server-side as **method=DEVICE**, the
  > same method cr-0019's L1 wrap uses — the server is operator-blind and cannot (and need not) distinguish
  > an Argon2id-`deviceFactor` wrap from a random-K wrap; both are an opaque DEVICE ciphertext, and both are
  > already deletable (the untrust whitelist is DEVICE). **How the client opens it is decided by the local
  > meta `level`**, not the server method: `onlyPassword` → `Argon2id(deviceFactor + password)`;
  > `biometric`/`noLogin` → `K` (read gated/ungated). One convenience wrap per device (mutually exclusive),
  > so there is never a DEVICE-method collision. This keeps cr-0020 **apple-only — no contract/backend/
  > vector change**.

- **Two Keychain items per account (so the level is knowable without a prompt).** An **ungated meta** item
  `{level, wrapId}` (read freely → the app knows the level and can render the right UI / decide whether to
  prompt) **plus** a **secrets** item `{key, refreshToken?}` gated per level (gated L2 / ungated L1·L3).
  This generalises cr-0019's `DeviceTrustStore` into a **`DeviceSecurityStore`** (L1's `deviceFactor`
  becomes the L1 secret, ungated, no token); cr-0019's L1 behaviour and tests stay green.
- **Persisted session + app-launch auto-restore.** On launch, read the ungated meta: **L3** → read the
  ungated token + K, resume the session (`refresh()`), open the vault, land on **home** with no prompt;
  **L2** → prompt Face ID, then the same; **L0/L1** → the normal connect → sign-in flow (L1 still folds at
  login). Token expired/rotated-out or K/wrap missing → fall back to the **floor** (no self-wipe on a single
  server response). Reuse `withRefresh`.
- **Chooser: keep the radio list, activate L2/L3** (settled — minimal UI change; the vertical scale stays a
  later idea). `DeviceUnlockLevel.available` returns true for all four, **gated by hardware**: L2 is
  **hidden** where no biometric is enrolled (`LAContext.canEvaluatePolicy`); L3 requires a **device
  passcode** and a **confirmation** of its "no app-level protection" trade (ADR 0005 §13). Picking L2/L3
  enrols the K-wrap + persists the (gated/ungated) session.
- **Mutually exclusive per device** (ADR 0005 §9). One convenience wrap at a time: switching levels deletes
  the old wrap + clears its stored secrets, then enrols the new one. Untrust/down-to-L0 removes everything
  and drops the persisted session.

## Phases

- [x] **1 — storage + crypto-light (`DeviceSecurityStore`).** `DeviceTrust.swift` → `DeviceSecurity.swift`:
  `DeviceLevel` enum (L0–L3), `DeviceSecurityRecord {level, wrapId, secret, refreshToken?}`, a
  `DeviceSecurityStore` protocol (`currentLevel` — ungated meta read, no prompt; `load` — full incl.
  gated secrets; `save`; `clear`), and `KeychainDeviceSecurityStore` storing **two items per account**: an
  ungated **meta** `{level, wrapId}` + a **secrets** `{secret, refreshToken?}` gated per level
  (`.biometryCurrentSet` for L2 via `SecAccessControlCreateWithFlags`, else `WhenUnlockedThisDeviceOnly`).
  K-wrap stays **method=DEVICE** (no contract change). cr-0019 L1 migrated (`deviceFactor` → the L1
  `secret`, `isTrusted` = `currentLevel == .onlyPassword`); `VeyqClient` + tests + doubles renamed
  (`InMemoryDeviceSecurityStore`, `DeviceSecurityStoreTests`). **Done:** `swift test` 39/39 (2 env-gated
  skips) — store round-trip covers L1 + the L2/L3 record shape (key + token); `xcodebuild` macOS
  `BUILD SUCCEEDED`.
- [x] **2 — orchestration + unit tests.** Refined the storage split so token rotation works: the **refresh
  token lives in the ungated meta** (updatable prompt-free) and only the KEK secret is gated — added
  `DeviceSecurityStore.updateRefreshToken` + `Session.account`. `VeyqClient`:
  `setPasswordlessLevel(.biometric|.noLogin, email)` (random K → `symWrap` → `POST` (method=DEVICE, marker
  `kdfParams`) → persist `{K, current refresh token}`; replaces any prior convenience wrap; rollback on
  save failure), `unlockWithDeviceKey(email)` (K *is* the KEK, used directly — no Argon2id), `resumeSession`
  (read persisted token → `refresh()` → live session), `currentLevel`, and `refresh()` now **persists the
  rotated token** for an L2/L3 device so the next launch stays valid. **Done:** `swift test` 46/46 (2
  env-gated skips) — enrol L2/L3 → resume + device-key unlock round-trip; rotation persists the new token;
  level switch swaps the one wrap; L1 record rejected by the K-path; no-record/dead-token → false (floor);
  enrol rollback.
- [x] **3 — app-launch auto-restore + UI activation.** New `App/LaunchPrefs.swift` (UserDefaults
  `{lastInstance, lastAccount}` — non-secret; remembered on L2/L3 enrol, cleared on untrust). `AppModel`:
  a `.launching` phase + `bootstrap()` (read prefs → if L2/L3, `resumeSession` then `unlockWithDeviceKey`
  → **L3 silent → home**, **L2 Face ID → home**; missing → connect; unusable key → sign-in); `RootView`
  shows a `LaunchView` and runs `bootstrap()` via `.task`. The floor unlock generalised:
  `unlock(secretKey:, level:)` enrols per the chosen `DeviceUnlockLevel` (L1 → `trustThisDevice`; L2/L3 →
  `setPasswordlessLevel` + `LaunchPrefs.remember`). `DeviceSecurityChooser`: L2/L3 **activated** — L2
  offered only when `LAContext.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` (else a "Not
  available" row); "Coming soon" dropped. `HomeView` message generalised across levels. **Done:**
  `xcodebuild` macOS **and** iOS-sim `BUILD SUCCEEDED`; hermetic `swift test` 46/46. *(L3 enrol
  confirmation dialog + a passcode-set check are a phase-4/polish item; the live lifecycle drives it.)*
- [ ] **4 — live round-trip + screenshots + coverage/parity.** In-app UI round-trips on the iOS sim with
  **simulated Face ID** (`xcrun simctl spawn booted notifyutil -p com.apple.BiometricKit_Sim.pearl.match`
  to satisfy the L2 prompt): enrol L2 → relaunch → Face ID → home; enrol L3 → relaunch → straight to home;
  switch levels; down to floor. Plus the live data-layer wrap/resume round-trip (in-memory store). Fill the
  `biometric-unlock` + `auto-unlock` coverage (drop `status: planned`, apple refs) and add apple to
  `set-device-security`; flip apple ✅ for L2/L3/chooser in
  [platform-parity.md](../docs/crosscutting/platform-parity.md). **Check:** `swift test` green; UI green;
  `validate_coverage.py` green. *(L2 data-layer from the SPM CLI can't present Face ID — covered by the
  in-app UI round-trip + the in-memory unit tests, as cr-0019 did for the Keychain.)*

## Definition of done

On Apple, a user can set a device to **Biometric** (open with only Face ID / Touch ID) or **No login**
(open straight to home), in addition to cr-0019's L0/L1 — chosen from the existing chooser (L2 hidden
without biometric hardware; L3 explicit + passcode-gated). Both resume the session from the Keychain
(gated/ungated) and open the vault on launch (L2 after a biometric, L3 silently), falling back to the floor
when the key/token is unusable. Levels are mutually exclusive and switchable; operator-blindness holds at
every level (the server stores only ciphertext + an opaque token and cannot distinguish the levels).
Coverage for `uc.account.biometric-unlock` + `uc.account.auto-unlock` is green on apple and parity reflects
it. Full non-extractable Secure-Enclave/ECIES backing, the **vertical-scale** chooser, and the **Android**
passwordless twin remain separate, planned work. Keep this file as a record.

## Deferred (explicit)

- **Non-extractable SE / ECIES** backing for L2 (key never leaves the enclave) — a hardening over the
  gated-Keychain choice; real-device-only testing.
- **Vertical stepped-scale chooser** (the explored slider direction) — UI polish, separate.
- **Android L2/L3** (hardware Keystore + BiometricPrompt) and windows/linux — per-surface CRs.
