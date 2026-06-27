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

## Decision — addendum 2026-06-27: device unlock levels (the per-device slider)

§7 settled *automatic precedence among openable wraps*. This addendum settles the **product shape** the
clients expose for it: a **per-device "unlock level"** the user picks themselves, after the first floor
unlock, from an **ordered, mutually-exclusive** set — the *security of my own device is the user's call*,
the *server is not*.

8. **Four ordered levels; the user chooses one per device.**
   - **L0 — Secret Key each time** (no convenience wrap; the bare floor). The default state of a fresh
     device.
   - **L1 — Only password** — the **DEVICE** wrap (`KEK = Argon2id(device-bound factor + password)`).
     Type the password each sign-in; the Secret Key goes back in the kit.
     ([uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md))
   - **L2 — Biometric** — a **device-held random key** wrap, the key released only after a **live
     biometric** (Secure Enclave / Keystore, FaceID / BiometricPrompt). No password typed; the biometric
     gesture is the second factor. ([uc.account.biometric-unlock](../features/account/use-cases/uc.account.biometric-unlock.md))
   - **L3 — No login** — the **same** device-held-key wrap, but held **ungated** + a persisted session;
     the app opens straight to its content, gated only by the **OS lock screen**.
     ([uc.account.auto-unlock](../features/account/use-cases/uc.account.auto-unlock.md))

9. **Mutually exclusive per device — narrows §4's "combinable" for UX clarity.** A device carries **one**
   convenience wrap at a time (plus the always-present floor). Switching level **removes** the old
   convenience wrap and **adds** the new one — never re-encrypts content. (The crypto still permits
   multiple wraps; the *level* abstraction deliberately exposes one, so the user has a single clear
   "how does this device unlock" answer.)

10. **No default — the user must choose.** After the first floor unlock the client **asks** which level to
    apply, with **nothing pre-selected**; until the user chooses, the device stays at **L0** (the floor).
    This refines §7: rather than silently applying a precedence order, the convenience level is an
    **explicit, informed per-device choice**. The **floor stays always reachable** at every level
    (automatic fallback when a KEK source or persisted session is unusable).

11. **Passwordless levels (L2, L3) require a persisted session.** Without the password the client cannot
    mint a fresh session (the password signs the login challenge, ADR 0003), so the **refresh token** is
    persisted in the platform keystore — **gated** by the same biometric for L2, **ungated** for L3.
    Token expiry / rotation-out falls back automatically to the floor (L0). L1 needs **no** persistence —
    the password login mints a fresh session each time. This is the clean implementation seam between the
    "L1 only" slice and the "L2 + L3" slice.

12. **Operator-blindness is untouched at every level — including L3.** The level slider relaxes **only
    local** device defense-in-depth, never server trust. Even at L3 the server holds only ciphertext wraps
    + an opaque session token; it never gains the KEK, Master Key, Secret Key, or password. The
    justification is the **threat-model asymmetry**: the *server* is an **unknown, global** adversary (why
    operator-blind is non-negotiable), while the user's *own device* is a **smaller, known-circle** risk
    ("handed over / left unlocked"), so trading local friction for convenience there is legitimately the
    user's decision.

13. **L3 trades away app-level defense-in-depth entirely — explicit opt-in, never a default.** At L3 the
    only gate beyond the OS lock screen is gone; a found *unlocked* device opens the content. It is offered
    only as a clearly-labelled choice (and presumes a device passcode/biometric is set at the OS level).
    Considered and **declined** as any kind of default.

## Related / future

- **Cross-device onboarding (QR / push):** a new device is onboarded by an existing **unlocked** device
  over an **E2E channel the blind server only relays** — the QR/push carry the new device's *public* key
  (no secrets), the existing device encrypts the Master Key / a fresh wrap to it. Its own use-case + ADR;
  must carry the **E2E unlock-transfer**, **QR-login phishing** and **non-enumerability** (ADR 0003)
  constraints.
- **Device unlock levels** (addendum above) — the user-facing flow of choosing/switching a level is
  [uc.account.set-device-security](../features/account/use-cases/uc.account.set-device-security.md); the
  per-level mechanics are the four level use-cases it links.
