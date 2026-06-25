# UC: Reset password

- **ID:** uc.account.reset-password
- **Primary actor:** Memory Curator
- **Goal:** Reset a forgotten password using control of the account email plus the Secret Key-backed
  RECOVERY wrap.
- **Preconditions:** The account has a stored RECOVERY wrap; the user can access the account email inbox
  and still has the Secret Key.
- **Postconditions (success):** The PASSWORD wrap and auth verifier are replaced, the reset token is
  consumed, existing sessions are invalidated, and the RECOVERY wrap remains unchanged.

## Main flow
1. The user requests a reset for an email address through `POST /api/me/password-reset/request`.
2. The server always returns 204; if a matching recoverable account exists, it emails a one-time reset
   token to that address.
3. The client exchanges the token via `POST /api/me/password-reset/material`.
4. The server returns the account email and the RECOVERY wrap, gated by the token.
5. The user enters the Secret Key; the client unwraps the Master Key locally and re-wraps it under the
   new password.
6. The client confirms through `POST /api/me/password-reset/confirm` with the token, new password,
   replacement PASSWORD wrap and recovery-auth proof.
7. The server verifies the proof, replaces the PASSWORD wrap and auth verifier, consumes the token, and
   invalidates all sessions.

## Alternate / exception flows
- A1: Unknown email or account without a RECOVERY wrap -> request still returns 204 and sends no email.
- E1: Token invalid, expired or already consumed -> material or confirm is rejected (400).
- E2: Recovery-auth proof does not match the stored recovery commitment -> rejected (403).
- E3: The Secret Key is unavailable -> the client cannot produce the proof; the account remains locked
  unless another recovery path exists.

## Quality notes
- *Email token is not enough:* email control releases only the RECOVERY wrap. Confirm still requires proof
  that the client opened the Master Key with the Secret Key.
- *Auth reset, not key recovery:* this flow changes password authentication and the PASSWORD wrap. Broader
  key-access recovery stays in [recover key access](uc.account.recover-keys.md).
- *Non-enumerable request:* the request endpoint does not reveal whether an address exists or is recoverable.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key recovery, Secret Key, key hierarchy)
- Decisions: [AD-06](../../../decisions/README.md) (recovery), [AD-08](../../../decisions/README.md) (Secret Key)
- HTTP client: [uc.account.reset-password.http](../../../../../http/features/account/use-cases/uc.account.reset-password.http)
- Related use-cases: `uc.account.login`, `uc.account.change-password`, `uc.account.recover-keys`
