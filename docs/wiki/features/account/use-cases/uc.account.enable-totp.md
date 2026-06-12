# UC: Enable TOTP

- **ID:** uc.account.enable-totp
- **Primary actor:** Memory Curator
- **Goal:** Add TOTP as a second factor for the password login path.
- **Preconditions:** Authenticated; has a password set.
- **Postconditions (success):** TOTP is enabled; password login now requires a current code; passkey
  login is unaffected.

## Main flow
1. The user starts TOTP enrollment; the server generates a secret and the client shows a QR code /
   secret.
2. The user adds it to an authenticator app and enters a current code to confirm.
3. The server verifies the code and **enables** TOTP; subsequent **password** logins require a code.

## Alternate / exception flows
- A1: **Disable TOTP** (re-auth required) → password login no longer needs a code.
- E1: Wrong confirmation code → not enabled.

## Quality notes
- *Auth-only:* TOTP gates the **password auth** path; it never touches the Master Key or any unlock wrap.
- *Server-stored secret:* TOTP is server-verified, so the secret is **server-visible** — acceptable
  because it only strengthens auth, not content access (the operator stays content-blind).
- *Passkey unaffected:* passkey login is already strong and never requires TOTP ([AD-18](../../../decisions/README.md)).
- *Loss tolerance:* losing the authenticator does **not** lock the account — passkey and the
  key-recovery paths remain.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-18](../../../decisions/README.md) (TOTP for password login)
- Related use-cases: `uc.account.login`, `uc.account.manage-profile`
