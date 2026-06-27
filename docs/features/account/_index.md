# Feature: Account & auth

Account lifecycle and authentication: logging in, unlocking the Master Key, trusting devices, passkeys,
changing the password / Secret Key, and recovering access. **Operator-blind throughout** — the server
holds only **public** keys and ciphertext, never the password and never a content key.

- **Cross-cutting:** [Account & Key Model](../../crosscutting/account-key-model.md) (auth vs. unlock, the
  one-Master-Key/many-wraps model), [Security Model](../../crosscutting/security-model.md)
  (operator-blind, non-enumerability), [ADR 0003](../../decisions/0003-operator-blind-password-auth.md)
  (password-derived keypair + challenge-response), [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md)
  (unlock methods, device trust, re-lock & precedence).
- **Note:** *auth* (proving who you are to the server) and *unlock* (opening your Master Key on the
  device) are deliberately **separate layers** — they appear as separate use-cases here.

## Use-cases

- ★ [Account login (password)](use-cases/uc.account.login.md) — **refined**
- ★ [Unlock the Master Key (password + Secret Key)](use-cases/uc.account.unlock.md) — **refined** (L0 — the floor)

**Device unlock levels** — the per-device security slider ([ADR 0005 §8–13](../../decisions/0005-unlock-methods-and-device-trust.md)):

- ★ [Set this device's unlock level](use-cases/uc.account.set-device-security.md) — **refined** (the chooser + transitions; L0–L3, mutually exclusive, no default)
- ★ [Trust this device (only password)](use-cases/uc.account.trust-device.md) — **refined** (L1 — DEVICE wrap; web+backend done, apple cr-0019)
- ★ [Biometric unlock (FaceID / fingerprint)](use-cases/uc.account.biometric-unlock.md) — **refined** (L2 — secure-element key + persisted session; native, cr-0020)
- ★ [Auto unlock (no login)](use-cases/uc.account.auto-unlock.md) — **refined** (L3 — same wrap ungated; explicit opt-in, never a default)
- Log in with a passkey — planned (alternate auth + PRF-derived unlock; the **web** biometric path; [ADR 0005](../../decisions/0005-unlock-methods-and-device-trust.md))
- Onboard a new device from an existing one — planned (QR / push trigger; E2E unlock-transfer relayed by the blind server; [ADR 0005 → related/future](../../decisions/0005-unlock-methods-and-device-trust.md))
- Change password / Secret Key — planned (re-register auth key, re-wrap Master Key)
- Recover access with a recovery code — planned (RECOVERY wrap, reissue code)
- Manage active sessions — planned (list session families by their **E2EE** client-built name, revoke individually; unlock-gated; [ADR 0004](../../decisions/0004-session-tokens.md))
