---
id: cr-0024
title: Unify the passwordless-unlock mechanism across platforms (prompt-free data layer, app layer releases K)
status: done      # planned → in-progress → done (the file is kept as a record)
satisfies: [uc.account.biometric-unlock, uc.account.auto-unlock, uc.account.set-device-security]
adrs: ["0011", "0010", "0005", "0009", "0003"]
invariants: [operator-blind]
---

## Goal

Make the L2/L3 passwordless unlock the **same mechanism on every platform**, so an external reviewer (and a
single concept doc) can describe it once and only check the native-API mapping per surface. Today Apple and
Android diverge on **where the biometric gate sits**: Apple reads the biometric-gated key *inside* the data
layer; Android (pure-JVM `:client`, async `BiometricPrompt`) releases the key in the **app layer** and passes
it in. Adopt the Android layering everywhere
([ADR 0011](../docs/decisions/0011-uniform-passwordless-unlock-orchestration.md)): **the data layer never
prompts** and takes the device key `K` as bytes with an identical method set (incl. `autoRestore(email, K)`);
**the app layer owns the native secure store + biometric gate** and hands `K` in. No backend, contract, or
crypto-vector change — this is a **client-internal** re-layering. Operator-blindness is untouched.

## Context & constraints

- Concept (the single mechanism this CR realises):
  [passwordless-unlock-mechanism](../docs/features/account/enablers/en.account.passwordless-unlock.md).
- Decision: [ADR 0011](../docs/decisions/0011-uniform-passwordless-unlock-orchestration.md) (prompt-free data
  layer; app layer releases `K`) — generalises the constraint in
  [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md) (no shared code; `:client`
  is pure JVM) and orchestrates the levels of
  [ADR 0005 §8–13](../docs/decisions/0005-unlock-methods-and-device-trust.md).
- Builds on [cr-0023](cr-0023-gate-l2-session-token.md) (token sealed under `K`) — the property that lets the
  data layer stay ciphertext-only and prompt-free.
- Use-cases: [biometric-unlock](../docs/features/account/use-cases/uc.account.biometric-unlock.md),
  [auto-unlock](../docs/features/account/use-cases/uc.account.auto-unlock.md).
- **Invariant at risk: operator-blind.** Guard: the change relocates only a *client-internal* boundary; the
  server still stores ciphertext wraps + an opaque sealed token. No new field crosses the wire.
- **Security-sensitive path.** The Apple resume/auto-restore refactor must keep: exactly one prompt at
  launch, prompt-free mid-session rotation, L3 silent, and the always-reachable floor fallback.
- Out of scope: web (no persisted session — already conformant; L2-web is N/A), and the Windows/Linux UI
  heads (they implement against the concept doc when they land — listed in the ledger as pending).

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Target state after this CR | Status |
|---|---|---|
| docs (ADR 0011 + concept doc) | the one mechanism written down | ✅ (phase 1) |
| backend / http | unchanged (client-only) | **N/A** |
| web | data layer already prompt-free; no persisted session | **N/A** (conformant) |
| apple | `K` released in `AppModel`; data layer prompt-free; `resumeSession`/`unlockWithDeviceKey`/`autoRestore` take `K` | ✅ (phase 3) |
| android | add `autoRestore(email, K)` to `:client`; `bootstrap` calls it (already passes `K` in) | ✅ (phase 2) |
| windows / linux | implement against the concept doc | ◻ (no UI head yet) |

## Phases

- [x] **1 — state (decision + concept doc).** Landed [ADR 0011](../docs/decisions/0011-uniform-passwordless-unlock-orchestration.md)
  and [passwordless-unlock-mechanism](../docs/features/account/enablers/en.account.passwordless-unlock.md); cross-linked from
  [platform-parity](../docs/crosscutting/platform-parity.md), [account-key-model](../docs/features/account/enablers/en.account.key-model.md),
  and the L2/L3 + chooser use-cases. **Done:** docs exist, links resolve, the mechanism matches the agreed
  layering.
- [x] **2 — android (make the surface identical).** Added `autoRestore(email, k: ByteArray)` to `:client`
  `VeyqClient` (level guard + `resumeSession(k)` + `unlockWithKey(k)`; returns `false` → floor). `:app`
  `AppModel.bootstrap` now calls it after releasing `K`, with the same fallback as Apple (record gone →
  connect; else → sign-in). Added 3 `:client` tests (`autoRestoreResumesAndOpensFromSuppliedKey`,
  `autoRestoreRejectsAnL1Record`, `autoRestoreWithDeadTokenReturnsFalse`). **Done:** `:crypto:test` +
  `:client:test` + `:app:assembleDebug` `BUILD SUCCESSFUL`; `bootstrap` no longer inlines the resume+unlock
  pair.
- [x] **3 — apple (move the gate to the app layer).** Reworked the `DeviceSecurityStore` protocol to the
  ungated data-layer view (`currentLevel`/`wrapId`/`refreshToken`/`updateRefreshToken`/`beginEnrol`/
  `deviceFactor`/`commitOnlyPassword`/`commitPasswordless`/`clear`) — the exact twin of Android's interface;
  dropped `load`/`save`/`DeviceSecurityRecord`. `KeychainDeviceSecurityStore` gained the **app-layer-only**
  `storePasswordlessKey` / `releasePasswordlessKey` (the single gated Keychain read). `VeyqClient`
  `resumeSession`/`unlockWithDeviceKey`/`autoRestore`/`setPasswordlessLevel` now take `K: Data` and never
  read the gated secret. `AppModel` owns the store, releases `K` in `bootstrap` (then `autoRestore(email,
  K)`) and creates+stores `K` in `unlock` (then `setPasswordlessLevel(level, email, K)`); `ConnectView`
  injects the same store. Replaced the obsolete `loadCount` test with `testAutoRestoreResumesAndOpensFrom`
  `SuppliedKey` (the single-gesture property is now an app-layer concern, mirroring Android). **Done:**
  `swift test` 51 (3 live-skip), 0 failures; macOS + iOS `xcodebuild BUILD SUCCEEDED`; the data layer has no
  Keychain-prompting path.
- [x] **4 — parity check + on-device.** Parity reconciled: the
  [matrix](../docs/crosscutting/platform-parity.md) L2/L3 entries (apple ✅ / android ✅) still hold — this CR
  changed the *internal layering*, not which surface implements the behaviour, so no matrix status moved; the
  surfaces now share one documented mechanism. **Deferred to the native catch-up (shared with
  [cr-0023](cr-0023-gate-l2-session-token.md) phase 4):** the on-device single-prompt round-trip — one prompt
  at L2, silent L3, prompt-free rotation, floor fallback — on both platforms. The code + unit proxies are
  complete, so this CR closes now; the hardware confirmation lands with the native catch-up
  ([platform-parity](../docs/crosscutting/platform-parity.md)).

## Definition of done

The passwordless unlock follows **one** documented mechanism on apple + android: a prompt-free data layer
with an identical method surface (`setPasswordlessLevel`/`resumeSession`/`unlockWithKey`/`autoRestore`, all
taking `K`), the biometric gate solely in the app layer, and the single concept doc + ADR 0011 describing it.
All suites green on both platforms; operator-blindness and the one-prompt / silent-L3 / floor-fallback
behaviours preserved; web confirmed conformant (no persisted session); windows/linux carry the doc as their
spec. The on-device M2 verification is **deferred to the native catch-up** ([platform-parity](../docs/crosscutting/platform-parity.md)) — not a blocker for closing this CR.
