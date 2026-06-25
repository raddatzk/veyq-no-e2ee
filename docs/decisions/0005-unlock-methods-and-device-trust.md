# ADR 0005 — Account unlock: one Master Key, many wraps; device trust, re-lock & method precedence

- Status: Accepted
- Date: 2026-06-25

## Context

[ADR 0003](0003-operator-blind-password-auth.md) split **auth** from **unlock**;
[ADR 0004](0004-session-tokens.md) settled **sessions**. This ADR settles the **unlock** side: how the
Master Key is opened on a device, how "stay unlocked" / device trust works **without** weakening 2SKD or
operator-blindness, and how a client chooses among several unlock methods.

Forces: operator-blind (the server holds only ciphertext wraps); the **2SKD floor** (password + Secret
Key); the **Secret Key must stay cold** (never at rest on a device); good UX (don't retype the Secret
Key, ideally passwordless on trusted devices); multi-surface parity (web/apple/android); **reload ≠
session end**.

## Decision

1. **One Master Key, many wraps.** Exactly one Master Key per account; **one `MasterKeyWrap` per unlock
   method** (`PASSWORD`, `DEVICE`, `PASSKEY`, `RECOVERY`). Adding/removing a method adds/removes **one
   wrap**; content is never re-encrypted. (Binding basis: [account-key-model](../crosscutting/account-key-model.md).)

2. **The wrap blob is server-side (portable); the KEK source is what binds.** Every wrap (ciphertext)
   lives on the blind server and is fetchable by any authenticated client. What differs is whether a
   client can produce the **KEK** to open it — so a wrap is usable **only where its KEK source is
   present**.

3. **The Secret Key is never at rest on a device.** The `PASSWORD` wrap
   (`KEK = Argon2id(password + Secret Key)`) is the **floor** and the **first-unlock** path on a new
   device. The Secret Key authorizes creating a convenience wrap **once**, then is forgotten — it lives
   only in the Emergency Kit.

4. **Convenience wraps are device-local, backed per platform.**
   - **DEVICE wrap** — `KEK = device-bound key + password` → *"only password"*. Backing: **non-extractable
     WebCrypto key in IndexedDB** (web); **Keychain / Keystore** key (apple/android). Device-local; does
     **not** sync — each device enrols its own.
   - **Biometric** — *no password*. Web: **WebAuthn passkey + PRF** (hardware via the platform
     authenticator). Apple/Android: **Secure Enclave / Keystore** key gated by FaceID / BiometricPrompt,
     used **directly** — no passkey needed for local unlock.
   - **Portability spectrum of the KEK backing:** device-local HW key (1 device) → OS-synced passkey (one
     ecosystem + its browser) → third-party-manager passkey (cross-ecosystem). More portable = less
     hardware-bound. Wraps are **combinable** on the same Master Key (e.g. an Enclave wrap *and* a passkey
     wrap on one phone).

5. **"Non-extractable ≈ HSM *interface*, not HSM *grade*."** A non-extractable WebCrypto key is a
   **software** boundary (browser-enforced) that beats XSS *exfiltration*; a true hardware root exists
   only via passkey / Enclave / StrongBox.

6. **Re-lock policy — two triggers, no periodic expiry.** The unlocked Master Key is cached for the
   session in a **worker** (survives reload, non-extractable, dies on real close). Re-lock on **(a) idle
   timeout** and **(b) session end** (tab/app close). **Reload ≠ session end.** (Periodic trusted-device
   expiry was considered and **dropped**.)

7. **Method precedence is client-side; the server never picks.** The client offers the wraps it can open
   **locally**; if more than one, the default order is **device-local biometric → synced passkey →
   device + password → password + Secret Key**. **User preference overrides and is remembered per
   device**; the **password + Secret Key floor is always reachable** ("unlock another way"). A synced
   passkey present on a fresh device enables immediate unlock there.

## Consequences

- **Operator-blind preserved.** The server holds only ciphertext wraps + opaque metadata — no KEK, Master
  Key, or Secret Key is ever server-side.
- **2SKD intact.** The Secret Key is never persisted; convenience replaces it with a *device-bound factor
  + (password or biometric)*.
- **Platform-agnostic crypto.** Identical backend/contracts; only the client **KEK adapter** differs per
  surface — good for parity.
- **UX.** Secret Key only on a *new* device; thereafter password (web / either) or biometric
  (passkey / native); a reload costs nothing.
- **Costs / limits.** Device-local wraps don't travel (cross-device = a passkey or per-device enrolment);
  synced / 3rd-party passkeys trade hardware-binding for reach; non-extractable is a *software* boundary;
  a live in-session XSS can still abuse an unlocked key / read plaintext — wraps protect against **key
  theft**, not **session misuse**.
- **Rejected — raw Secret Key in localStorage:** portable, exfiltratable, no real gain over a device-bound
  key. **Rejected — periodic trusted-device expiry:** dropped per product choice. **Rejected —
  server-side method selection:** kept client-side for blindness + parity.

## Related / future

- **Cross-device onboarding (QR / push):** a new device is onboarded by an existing **unlocked** device
  over an **E2E channel the blind server only relays** — the QR/push carry the new device's *public* key
  (no secrets), the existing device encrypts the Master Key / a fresh wrap to it. Its own use-case + ADR;
  must carry the **E2E unlock-transfer**, **QR-login phishing** and **non-enumerability** (ADR 0003)
  constraints.
