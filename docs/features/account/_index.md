# Feature: Account & auth

Account lifecycle and authentication: logging in, unlocking the Master Key, trusting devices, passkeys,
changing the password / Secret Key, and recovering access. **Operator-blind throughout** — the server
holds only **public** keys and ciphertext, never the password and never a content key.

- **Cross-cutting:** [Account & Key Model](../../crosscutting/account-key-model.md) (auth vs. unlock, the
  one-Master-Key/many-wraps model), [Security Model](../../crosscutting/security-model.md)
  (operator-blind, non-enumerability), [ADR 0003](../../decisions/0003-operator-blind-password-auth.md)
  (password-derived keypair + challenge-response).
- **Note:** *auth* (proving who you are to the server) and *unlock* (opening your Master Key on the
  device) are deliberately **separate layers** — they appear as separate use-cases here.

## Use-cases

- ★ [Account login (password)](use-cases/uc.account.login.md) — **refined**
- ★ [Unlock the Master Key (password + Secret Key)](use-cases/uc.account.unlock.md) — **refined**
- Log in with a passkey — planned (alternate auth + PRF-derived unlock)
- Trust this device — planned (DEVICE wrap, skip Secret Key re-entry)
- Change password / Secret Key — planned (re-register auth key, re-wrap Master Key)
- Recover access with a recovery code — planned (RECOVERY wrap, reissue code)
- Manage active sessions — planned (list session families by their **E2EE** client-built name, revoke individually; unlock-gated; [ADR 0004](../../decisions/0004-session-tokens.md))
