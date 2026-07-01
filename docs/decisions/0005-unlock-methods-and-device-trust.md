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
   wrap**; content is never re-encrypted. (Binding basis: [en.account.key-model](../features/account/enablers/en.account.key-model.md).)

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

## Decision — device unlock levels (the per-device slider)

§7 settled *automatic precedence among openable wraps*; the clients expose that as a **per-device "unlock
level"** the user picks themselves, after the first floor unlock, from an **ordered, mutually-exclusive**
set — the *security of my own device is the user's call*, the *server is not*.

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

## Decision — web product shape (L0 not a routine choice; "remember me")

14. **L0 is not a routine, selectable level; it stays the floor (bootstrap + emergency fallback).** Asking
    for the Secret Key on *every* unlock is the wrong friction for a memory-sharing app (not a secrets
    vault). The Secret-Key floor (§3) remains, but only as (a) the **first-unlock / bootstrap** on a device
    (typed **once per device**, until cross-device onboarding lands) and (b) the **always-reachable
    emergency fallback** when a convenience method's KEK source is lost — operator-blindness means **no
    operator-side reset**, so removing it would mean permanent lockout. §8/§10's chooser therefore **does
    not offer "Secret Key each time"** as a standing option.

15. **Web presents the levels as "auth method + remember me", not a four-stop slider.** Auth method =
    **password** (Model B, ADR 0003) or **passkey** (WebAuthn + PRF — its own future UC, §16). **"Remember
    me"** = the opt-in into **L3** ([uc.account.auto-unlock](../features/account/use-cases/uc.account.auto-unlock.md)):
    an ungated persisted session (the [ADR 0004](0004-session-tokens.md) cookie) + an ungated device-held
    key wrapping the Master Key → the device opens straight in across a full browser restart. **Off** =
    re-authenticate each launch (no persisted session/vault). This is a **web presentation** of the same
    wraps (§9: one convenience wrap per device); native keeps its biometric framing. L3's opt-in + warning
    (§13) is exactly the "remember me" toggle's trade-off.

16. **Passkey auth and QR cross-device onboarding are deferred to their own use-cases/ADRs.** Passkey is a
    new WebAuthn **auth** path beyond ADR 0003's Model B (plus PRF for the unlock KEK, §4); QR onboarding is
    the cross-device bootstrap already noted below in *Related / future*. Neither is part of the
    session-persistence increment (the cookie + remember-me / L3 on the existing password path).

## Decision — one combined login/unlock screen, auto-enrol; web's single "remember me" toggle

This concretizes §15: the login "remember me" and unlock "trust this device" choices are two views of the
*same* per-device decision (§9). §17-18 (the combined screen + auto-enrol) are **cross-surface**; §19-20 and
the single-toggle presentation are the **web** shape.

17. **Login + unlock collapse into one screen wherever a device wrap exists — on *every* surface, not just
    web.** The same password both **authenticates** (the Model B auth keypair, ADR 0003) **and** derives the
    **DEVICE-wrap KEK** (§4) — so entering email + password does login *and* unlock in a single step. Hence the
    adaptive screen: the **first** sign-in on a device adds the **Secret Key** field (the floor, §3); once a
    wrap exists it is **email + password only**; a remembered / biometric device shows **no screen at all**. So
    the **Secret Key is typed once per device** (§3, §14). (Mobile therefore also has one combined login/unlock
    screen — this is not a web-only simplification.)

18. **"Remember me" IS the per-device choice on web — it replaces both prior checkboxes.** On a successful
    first unlock the client **auto-enrols** a convenience wrap from the in-memory Master Key (the Secret Key is
    never stored):
    - **Off** → a **DEVICE wrap** (`KEK = Argon2id(device-bound key + password)`) → next launch: **password
      only**, no Secret Key. (The L1 mechanism, but *automatic* — not a second opt-in.)
    - **On** → a **password-less device-key wrap** (§20) + a persisted session ([ADR 0004](0004-session-tokens.md)
      cookie) → next launch: **opens straight in**. (L3.)

19. **No "remember nothing" / public-device level on web — "every device is mine" (refines §14 for web).**
    Even remember-me-off enrols the password DEVICE wrap; there is **no** L0 "Secret Key each time" option on
    web. Justification: a stolen device + its DEVICE wrap is **useless without the password** (still gated),
    and a genuinely untrusted machine is covered by the browser's **private/incognito** mode (no persisted
    IndexedDB / cookie). The Secret-Key floor stays only as the **first-device bootstrap + emergency recovery**
    (§14), never a routine web pick.

20. **A new `MasterKeyWrap.Method` for the password-less device-key wrap.** The existing `DEVICE` method mixes
    the password (`Argon2id(deviceFactor + password)`); the remember-me wrap is the **device key alone**
    (`symWrap(K, masterKey)` — high-entropy, no password, no KDF), a distinct construction → a **distinct
    method** (name TBD, e.g. `DEVICE_KEY`). It is the same wrap native uses for L2 (biometric-gated) and L3
    (ungated); only the local keystore gating differs (§8-9).

21. **The surfaces differ only in the *set* of convenience options, not the screen.** Both share the one-screen
    login/unlock (§17) and the auto-enrol on first unlock (§18). **Web** offers two, via a single **"remember
    me"** toggle — the password DEVICE wrap (off) or the password-less wrap (on),
    [uc.account.remember-this-device](../features/account/use-cases/uc.account.remember-this-device.md).
    **Native** adds a third — **biometric** — realistic on a phone, via its chooser
    ([uc.account.set-device-security](../features/account/use-cases/uc.account.set-device-security.md)). Same
    wraps (§9), same combined screen; only the option set + its presentation (a toggle vs a chooser) differ.
    Whether native also collapses to a single toggle is **to be worked out**.

## Decision — web "off" is session-scoped (survives a reload, dies on browser close)

This refines §15/§18's "**Off**" for web: it persists for the **browser session** — a no-"remember me" session
survives page reloads and ends only when the browser closes, matching the conventional web norm (rather than an
in-memory-only session that dies on the first reload).

22. **Web "off" = the conventional session tier; "on" = persistent.** Off persists for the **browser session**,
    on persists across a **browser restart**:
    - **Off** → a **session refresh cookie** (no `Max-Age`, [ADR 0004](0004-session-tokens.md)) **+** the
      unlocked Master Key cached in **`sessionStorage`**. A reload re-opens the vault with **no prompt**; closing
      the browser drops both. Off **also** keeps its password **DEVICE** wrap (§18), so the *next launch after a
      close* is **email + password only** — never the Secret Key.
    - **On** → the **persistent** cookie + the password-less **`DEVICE_KEY`** wrap in IndexedDB (§18-20) → opens
      straight in across a full restart.
    - The Secret-Key floor is still typed **once per device** (§3, §14, §17); neither tier re-asks it.

23. **The session-scoped relaxation is conscious and bounded.** Within an open browser session, off re-opens the
    vault without the password — the device-held key opens it (the convenience this tier exists for). The Master
    Key is sealed under a **non-extractable** AES-GCM key in IndexedDB and only the **ciphertext** sits in
    `sessionStorage`. So, like every other device tier, the key's bytes never enter JS: an XSS payload can
    decrypt while it runs but
    cannot exfiltrate the key, and **nothing plaintext is ever written to `sessionStorage`** — which matters
    because the browser's session-restore may persist `sessionStorage` to disk. Browser close clears
    `sessionStorage` → the ciphertext is gone and the orphaned (un-exfiltratable, now-useless) key is dropped on
    the next boot. This is the standard web-session posture; for a **memory-sharing** app (not a secrets vault,
    §14) it is the right friction. **Operator-blind is untouched** — the server still holds only ciphertext wraps
    + an opaque cookie token; the relaxation is purely local. A genuinely untrusted machine still uses
    **private/incognito** (§19).

## Related / future

- **Cross-device onboarding (QR / push):** a new device is onboarded by an existing **unlocked** device
  over an **E2E channel the blind server only relays** — the QR/push carry the new device's *public* key
  (no secrets), the existing device encrypts the Master Key / a fresh wrap to it. Its own use-case + ADR;
  must carry the **E2E unlock-transfer**, **QR-login phishing** and **non-enumerability** (ADR 0003)
  constraints.
- **Device unlock levels** (§8–13 above) — the user-facing flow of choosing/switching a level is
  [uc.account.set-device-security](../features/account/use-cases/uc.account.set-device-security.md); the
  per-level mechanics are the four level use-cases it links.
