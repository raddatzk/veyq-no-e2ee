---
id: cr-0023
title: Gate the L2 persistent session token (encrypt the persisted refresh token under K)
status: planned          # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.biometric-unlock, uc.account.auto-unlock]
adrs: [0005, 0004, 0003]
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

- [ ] **1 — crypto + vector.** Add the `"session-token"` context usage and a `wrap.session_token` vector to
  `contracts/crypto/vectors/v1.json`; make `:crypto:test` / `swift test` / web conformance green on all
  three stacks (it reuses `symWrap`/`symUnwrap`, so this is mostly the vector + a round-trip assert).
  **Verify:** `./gradlew :crypto:test`; `cd apple && swift test`; `cd web && npm run check`.
- [ ] **2 — Apple.** Keep K resident for the session; `persistRotatedTokenIfPasswordless` seals under K;
  `resumeSession`/`autoRestore` unseals; `DeviceSecurity` stores the sealed token in the (still-ungated)
  meta — the gated `secret` item is untouched. Add the lazy plaintext→sealed migration. Unit-test the
  seal/rotate/unseal round-trip against the in-memory `DeviceSecurityStore` double.
  **Verify:** `swift test` (round-trip incl. migration); iOS `xcodebuild` build.
- [ ] **3 — Android.** Same in `:client` (`VeyqClient` holds K for the session, seals/unseals around the
  store) — the plaintext token leaves `SharedPreferences` entirely (only ciphertext persists). `:app`
  supplies K from the BiometricPrompt path as today.
  **Verify:** `./gradlew :client:test` (round-trip + migration); `:app:assembleDebug`.
- [ ] **4 — on-device auto-restore verification (the part unit tests can't cover).** Confirm on a real
  biometric device / emulator that L2 app-launch auto-restore shows **exactly one** biometric prompt and
  lands on home, mid-session rotation never prompts, and L3 still restores silently. Apple: the live
  in-app `RoundTripUITests`; Android: `:app:connectedDebugAndroidTest` (`adb emu finger touch`).

## Done when

L2 auto-restore is gated end-to-end (the persisted token is useless without the biometric-gated K), token
rotation stays prompt-free, exactly one biometric prompt at launch, L3 unchanged, all suites green, and the
on-device round-trip confirmed on both platforms. Then update the `security-review-2026-06` memo to move M2
from deferred → done.
