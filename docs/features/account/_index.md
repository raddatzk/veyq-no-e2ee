# Feature: Account & auth

Account lifecycle and authentication: logging in, unlocking the Master Key, trusting devices, passkeys,
changing the password / Secret Key, and recovering access. **Operator-blind throughout** — the server
holds only **public** keys and ciphertext, never the password and never a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md)
  (operator-blind, non-enumerability), [ADR 0003](../../decisions/0003-operator-blind-password-auth.md)
  (password-derived keypair + challenge-response), [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md)
  (unlock methods, device trust, re-lock & precedence).
- **Note:** *auth* (proving who you are to the server) and *unlock* (opening your Master Key on the
  device) are deliberately **separate layers** — they appear as separate use-cases here.

## Enablers

- ★ [en.account.key-model](enablers/en.account.key-model.md) — the account key model: identity + the one
  **Master Key** (many wraps), the unlock methods + KEK derivation, and recovery; the **root** the scope/content
  keys extend.
- ★ [en.account.passwordless-unlock](enablers/en.account.passwordless-unlock.md) — the **L2/L3
  passwordless-unlock** orchestration (two-layer split, native per platform; the single security-reviewer
  reference).
- [en.account.identity-pinning](enablers/en.account.identity-pinning.md) — the operator-blind, cross-device
  **TOFU pin store** (one Master-Key-sealed blob) that remembers each contact's identity key, so a substituted
  key is caught on change (**web built, cr-0098**).

## Use-cases

- ★ [Account login (password)](use-cases/uc.account.login.md) — **refined**
- ★ [Unlock the Master Key (password + Secret Key)](use-cases/uc.account.unlock.md) — **refined** (L0 — the floor)

**Device unlock levels** — the per-device security slider ([ADR 0005 §8–13](../../decisions/0005-unlock-methods-and-device-trust.md)):

- ★ [Set this device's unlock level](use-cases/uc.account.set-device-security.md) — **refined** (the chooser + transitions; L0–L3, mutually exclusive, no default)
- ★ [Trust this device (only password)](use-cases/uc.account.trust-device.md) — **refined** (L1 — DEVICE wrap; web+backend done, apple cr-0019)
- ★ [Biometric unlock (FaceID / fingerprint)](use-cases/uc.account.biometric-unlock.md) — **refined** (L2 — secure-element key + persisted session; native, cr-0020)
- ★ [Auto unlock (no login)](use-cases/uc.account.auto-unlock.md) — **refined** (L3 — same wrap ungated; explicit opt-in, never a default)
- ★ [Sign in & remember this device (web — one screen, one toggle)](use-cases/uc.account.remember-this-device.md) — **refined** (the web combined sign-in+unlock screen; a "remember me" toggle auto-enrols the L1 DEVICE or L3 device-key wrap so the Secret Key is typed once per device; [ADR 0005 §17–21](../../decisions/0005-unlock-methods-and-device-trust.md))
- Log in with a passkey — planned (alternate auth + PRF-derived unlock; the **web** biometric path; [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md))
- Onboard a new device from an existing one — planned (QR / push trigger; E2E unlock-transfer relayed by the blind server; [ADR 0005 → related/future](../../decisions/0005-unlock-methods-and-device-trust.md))
- Change password / Secret Key — planned (re-register auth key, re-wrap Master Key)
- Recover access with a recovery code — planned (RECOVERY wrap, reissue code)
- Manage active sessions — planned (list session families by their **E2EE** client-built name, revoke individually; unlock-gated; [ADR 0004](../../decisions/0004-session-tokens.md))

**Terms gate** — an *operational* gate that sits on a **session**, above unlock ([ADR 0035](../../decisions/0035-server-terms-of-service.md)):

- ★ [Accept the tenant's terms](use-cases/uc.account.accept-terms.md) — **built (web, cr-0114)** (a hard member gate on a stale `accepted_terms_version`; server-side, re-accept on a bump; the operator sets the version in [uc.admin.tenant-terms](../admin/use-cases/uc.admin.tenant-terms.md))
