---
id: cr-0023
title: Gate the L2 persistent session token (encrypt the persisted refresh token under K)
status: in-progress      # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.biometric-unlock, uc.account.auto-unlock]
adrs: ["0005", "0004", "0003"]
invariants: [operator-blind]
---

## Goal

Close security-review finding **M2**: on a **biometric (L2)** device the persisted **refresh token** is
stored **ungated** (Apple: the `meta` Keychain item; Android: plain `SharedPreferences`), while only the
wrap key **K** is biometric-gated. So anyone with an **unlocked L2 device** (or malware with app-data
access, or a forensic extraction of an unlocked device) can read the token **without passing the Face ID /
fingerprint gate** and re-establish a live authenticated session — call account APIs, enumerate/delete
wraps, manage sessions.

Content stays safe (decrypting needs K, which is gated; the server stays operator-blind), so this is a
**session-identity** gap, not a content gap — but it contradicts the L2 promise ("nothing opens without
your biometric"). After this CR the L2 session identity sits **behind the same biometric gate as the
content**, with **no extra prompt** and **no UX regression to token rotation**.

L3 ("no login") is intentionally unaffected — there K is ungated by design (rides the OS lock screen), so
the token-under-K opens silently exactly as today.

## Context & constraints

- Finding source: the 2026-06 security review (see commits `425c2fe5`, `36df3025`; the review memo lists M2
  as the deferred item that needs on-device verification).
- The token is ungated **on purpose** today: `persistRotatedTokenIfPasswordless` runs after **every**
  `refresh()` — including mid-session 401-driven refreshes ([ADR 0004](../docs/decisions/0004-session-tokens.md):
  single-use rotating refresh tokens) — and must write the rotated token back **prompt-free**. Naively
  gating the token item would either fire a Face ID prompt on every mid-session rotation or force a
  double-prompt at launch.
- **Reuse the vector-pinned crypto:** the fix is `symWrap`/`symUnwrap` (`:crypto` / `VeyqCrypto` / web
  `v1.ts`) with a **new context string `"session-token"`** — no new primitive.
- **Invariant:** operator-blind unchanged — the server still holds only ciphertext wraps + an opaque token
  it issued; it never sees K, the Master Key, or the (now-encrypted-at-rest) token.
- Affects **Apple ([cr-0020](cr-0020-apple-passwordless-levels.md))** and **Android
  ([cr-0022](cr-0022-android-passwordless-levels.md))**; web persists no session (in-memory tokens only),
  so it is out of scope.

## Design decisions (settled)

- **Encrypt the persisted token under K, not in a second gated item.** Store
  `sealed = symWrap(K, utf8(token), "session-token", randomNonce(12))` where the **plaintext** token lives
  today (Apple `meta` item / Android prefs). The store layer keeps treating it as an **opaque string** — it
  only ever sees ciphertext. This deliberately moves the fix from the **untestable Keychain/biometric
  gating layer** onto the **unit-testable AEAD layer**.
- **Why this resolves all three constraints at once:**
  - *Prompt-free rotation*: keep **K in memory** for the unlocked session (analogous to the Master Key,
    already resident; K only wraps it, so no new exposure). Mid-session rotation re-seals the new token with
    the in-memory K — pure AES-GCM, **no Keystore/Keychain use, no prompt**.
  - *One prompt at launch, no chicken-and-egg*: K is the **root** of the chain and comes straight from the
    gated store (no server round-trip). Order: biometric → unseal K (the single prompt) → `symUnwrap` token
    with K (in-memory) → install + `refresh()` (rotation re-seals prompt-free) → fetch key-material →
    unwrap Master Key with K → open vault. ("Encrypt under the Master Key" was rejected — it deadlocks: the
    token is needed for the session that is needed to fetch the wrap that yields the Master Key.)
  - *Testable*: a `wrap.session_token` conformance vector pins the bytes across all three stacks; only the
    single-prompt UX + auto-restore happy path need the live device run.
- **Consolidate the launch reads.** Today `resumeSession` and `unlockWithDeviceKey` each call the gated
  `load()` → two gated reads (a latent double-read/prompt). Replace with one `autoRestore(email)` that loads
  K **once** behind one prompt and reuses it for both the token unseal and the vault unwrap.
- **Migration.** Existing L2/L3 records hold a **plaintext** token. On first launch after the update,
  lazy-upgrade: if the stored token doesn't parse as a `session-token` blob, treat it as plaintext, use it
  once, then re-seal under K on the next rotation. (Fallback: force a re-enrol — acceptable but worse UX.)
- **Scope guard.** No backend / contract / vector-format change (only an added vector). K stays the KEK; the
  server still stores method=DEVICE wraps + an opaque token.

## Phases

- [x] **1 — crypto + vector.** Added the `wrap.session_token` vector to `contracts/crypto/vectors/v1.json`
  (reuses `symWrap`/`symUnwrap` with context `"session-token"`) + a round-trip assert in all three
  conformance suites. **Done:** web conformance 20/20; apple `testSymWrap` (sym_master_key + session_token);
  android `:crypto:test` `BUILD SUCCESSFUL`.
- [x] **2 — Apple.** `Vault` holds K for the session (`holdDeviceKey`/`deviceKey`, zeroed on `clear`);
  `persistRotatedTokenIfPasswordless` seals under the held K (skips, never plaintext, if K absent);
  `setPasswordlessLevel` stores the sealed token; `resumeSession` unseals (lazy plaintext→sealed migration);
  new consolidated **`autoRestore`** loads K once and reuses it for resume + vault-open (one prompt);
  `bootstrap` switched to it. **Done:** `swift test` 49 (3 live-skip), incl. new
  `testLegacyPlaintextTokenIsMigratedToSealedOnResume` + `testAutoRestoreResumesAndOpensInOneLoad`; iOS
  `xcodebuild` `BUILD SUCCEEDED`.
- [x] **3 — Android.** Same in `:client` (`Vault` holds K; `VeyqClient` seals in `setPasswordlessLevel`,
  unseals in `resumeSession(email, k)` — signature now takes K, re-seals on rotation) — the plaintext token
  leaves `SharedPreferences` entirely. `:app` `bootstrap` reordered to obtain K **first** (the single
  BiometricPrompt / ungated read), then `resumeSession(acct, k)` + `unlockWithKey(acct, k)`. **Done:**
  `:client:test` (incl. new `legacyPlaintextTokenIsMigratedToSealedOnResume`) + `:app:assembleDebug`
  `BUILD SUCCESSFUL`.
- [~] **4 — on-device auto-restore verification (the part unit tests can't cover).** Confirm on a real
  biometric device / emulator that L2 app-launch auto-restore shows **exactly one** biometric prompt and
  lands on home, mid-session rotation never prompts, and L3 still restores silently. Apple: the live
  in-app `RoundTripUITests`; Android: `:app:connectedDebugAndroidTest` (`adb emu finger touch`).
  **De-risked at the unit level (the real biometric run still needs hardware — not available in CI here):**
  - Apple: the `InMemoryDeviceSecurityStore` now counts `load` calls (the read that prompts L2), and
    `testAutoRestoreResumesAndOpensInOneLoad` asserts `autoRestore` does **exactly one** gated load — the
    direct unit proxy for "one prompt" (vs. the two that `resumeSession` + `unlockWithDeviceKey` caused).
  - Android: the single prompt is structural — `:app` `bootstrap` calls `BiometricGate.authenticate` once
    and passes the resulting K to both `resumeSession(k)` and `unlockWithKey(k)`; `:client` does no gating.
  **Still outstanding:** the actual Face ID / fingerprint round-trip on device (one prompt observed, silent
  L3) — run when hardware is available, then flip status → done.

## Done when

L2 auto-restore is gated end-to-end (the persisted token is useless without the biometric-gated K), token
rotation stays prompt-free, exactly one biometric prompt at launch, L3 unchanged, all suites green, and the
on-device round-trip confirmed on both platforms. Then update the `security-review-2026-06` memo to move M2
from deferred → done.
