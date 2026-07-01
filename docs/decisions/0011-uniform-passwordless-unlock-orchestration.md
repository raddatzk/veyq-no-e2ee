# ADR 0011 — Uniform passwordless-unlock orchestration: prompt-free data layer, app layer releases the device key

- Status: Accepted
- Date: 2026-06-27

## Context

[ADR 0005](0005-unlock-methods-and-device-trust.md) settled the per-device **unlock levels** (L0–L3) and
that the passwordless levels (L2 biometric / L3 no-login) ride a **device-held random key `K`** that wraps
the Master Key, plus a **persisted refresh token** (§11). [ADR 0010](0010-native-clients-shared-contracts-not-code.md)
settled **no shared client code — parity via shared contracts**, with each surface implemented in its own
idiomatic stack and the data layer reusable headless. [ADR 0009](0009-native-crypto-per-platform-vectors.md)
pins the crypto per platform via vectors.

A passwordless unlock spans **two layers**:

1. a **crypto + network orchestration** (unseal the token, resume the session, fetch material, unwrap the
   Master Key with `K`, open the vault), and
2. a **native secure-store + biometric gate** that releases `K` (Secure Enclave / Keystore, Face ID /
   BiometricPrompt).

The first native implementations diverged on **where the gate sits relative to the data layer**:

- **Apple** put the gate *inside* the data layer — `DeviceSecurityStore.load()` reads the biometric-gated
  Keychain item, so the data layer triggers the Face ID prompt and obtains `K` itself (and owns
  `autoRestore`).
- **Android** put the gate *in the app layer* — `:client` is **pure JVM with no Android SDK** (ADR 0010)
  and `BiometricPrompt` is **Activity-bound + asynchronous**, so the prompt cannot live in the data layer.
  The app obtains `K` and passes it into `resumeSession(email, K)` / `unlockWithKey(email, K)`.

This divergence makes the mechanism **platform-specific to describe, review and document**, and it does not
scale: Windows (Windows Hello), Linux (Secret Service / fprintd) and any future surface would each invent
their own boundary. We need **one** layering that every platform follows with its own native APIs, so an
external reviewer studies **one** mechanism and only checks the per-platform API mapping.

## Decision

**One layering for every platform: the data layer never prompts; the app layer releases `K` and hands it
in.** Concretely:

1. **The data layer never triggers a user-facing prompt.** It speaks only crypto + network. It takes the
   device key `K` (and, for L1, the `deviceFactor`) as **plain bytes** and exposes the **same method set on
   every platform**, including a single consolidated `autoRestore(email, K)` (resume the session + open the
   vault from one already-released `K`).
2. **The platform app/UI layer owns the native secure store and the biometric / secure-element gate.** It
   reads the **ungated level**, performs the native gesture to **release `K`**, then calls the data layer.
3. **The secure store is split in two:** an **ungated meta** part `{level, wrapId, sealed refresh token}`
   readable **without a prompt**, and a **gated secret** `{K}` released **only** by the native gesture in
   the app layer. The level is always knowable without a prompt (so the app can decide what UI to show).
4. **The persisted refresh token is sealed under `K`** ([cr-0023](../../changes/cr-0023-gate-l2-session-token.md)):
   the data/store layers only ever see **ciphertext**, and `K` is held in memory for the unlocked session so
   mid-session rotation re-seals it **prompt-free**.
5. **"Exactly one prompt at launch" is therefore an app-layer property** — one gesture → one `K` → one
   `autoRestore` — **uniform across platforms** and verified per surface by **behaviour**, not by shared code
   (ADR 0010).

This **generalises the Android model to all platforms**. It is the right direction because it makes the
**weakest assumption** — "releasing `K` may be asynchronous and UI-bound" — which is the only one that holds
**everywhere** (it is *mandatory* on Android, *possible* on Apple). The reverse cannot be made uniform.

## Consequences

- **One concept doc** describes the mechanism for all surfaces
  ([en.account.passwordless-unlock](../features/account/enablers/en.account.passwordless-unlock.md)); per-platform docs only
  map native APIs. This is the externally-reviewable artifact the decision exists to enable.
- **Apple is refactored to match** ([cr-0024](../../changes/cr-0024-unify-passwordless-unlock-mechanism.md)):
  the `K`-release moves out of the data layer into the app layer (`AppModel`); `resumeSession` /
  `unlockWithDeviceKey` / `autoRestore` take `K` as a parameter; the gated Keychain read leaves the data
  layer's responsibility (the ungated meta read stays). Apple's just-added `loadCount` single-gated-load unit
  test is replaced by an **app-layer single-gesture assertion** (the property moved layers).
- **Android gains `autoRestore(email, K)` in `:client`** so both data layers expose an identical surface (the
  resume+unlock sequence stops being inlined in `bootstrap`).
- **Web is trivially conformant:** it persists **no** session (in-memory tokens, worker-cached Master Key),
  so its data layer is already prompt-free and L2/L3 persistence does not apply (L2-web is **N/A**, a future
  L3-web "stay signed in" would follow the same rule).
- **Windows / Linux implement against the one concept doc** — no per-platform boundary to invent.
- **Operator-blindness untouched** (the hard invariant): the server still holds only ciphertext wraps + an
  opaque, now-sealed session token; it never sees `K`, the Master Key, the Secret Key, or the password. The
  decision relocates a **client-internal** boundary only.
- **Cost / risk:** a refactor in a **security-sensitive path** on Apple (resume + auto-restore), to be
  carefully re-tested. Accepted for the uniformity payoff.
- **Rejected — unify the other direction (data layer owns an async gated `load()`).** It would force an
  Activity / async-prompt dependency into the **pure-JVM data-layer contract** (against ADR 0010) and make
  **every** data layer "know about prompting" — the opposite of an auditable, headless-reusable core.

## Related

- [ADR 0005](0005-unlock-methods-and-device-trust.md) — the unlock levels this orchestrates.
- [ADR 0010](0010-native-clients-shared-contracts-not-code.md) — no shared code; the constraint that forced
  the Android split and that this decision now makes uniform.
- [cr-0023](../../changes/cr-0023-gate-l2-session-token.md) — sealing the persisted token under `K` (the
  property that lets the data layer stay prompt-free and ciphertext-only).
- Concept: [en.account.passwordless-unlock](../features/account/enablers/en.account.passwordless-unlock.md).
- Use-cases: [biometric-unlock (L2)](../features/account/use-cases/uc.account.biometric-unlock.md),
  [auto-unlock (L3)](../features/account/use-cases/uc.account.auto-unlock.md),
  [set-device-security (chooser)](../features/account/use-cases/uc.account.set-device-security.md).
