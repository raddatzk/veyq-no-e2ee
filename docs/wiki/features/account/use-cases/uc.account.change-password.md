# UC: Change password

- **ID:** uc.account.change-password
- **Primary actor:** Memory Curator
- **Goal:** Change the account password and replace the PASSWORD wrap of the unchanged Master Key.
- **Preconditions:** Authenticated; knows the current password; the client has the Master Key unlocked
  locally so it can submit a new PASSWORD wrap.
- **Postconditions (success):** The auth verifier is derived from the new password, the PASSWORD
  `MasterKeyWrap` is replaced, and all existing sessions are invalidated.

## Main flow
1. The user enters the current password and chooses a new password.
2. The client keeps the Master Key unchanged and wraps it under the new password-derived KEK.
3. The client submits the current password, new password and new `PASSWORD` `MasterKeyWrap` to
   `POST /api/me/password`.
4. The server resolves the session and verifies the current password.
5. The server validates that the submitted wrap uses `unlockMethod = PASSWORD`, stores it, derives the
   new auth verifier, and invalidates all sessions.
6. The client must authenticate again with the new password to continue.

## Alternate / exception flows
- E1: Session missing or invalid -> rejected (401).
- E2: Current password is wrong -> rejected (401).
- E3: Submitted wrap is not a `PASSWORD` wrap or uses an unsupported suite -> rejected (400).
- E4: The account has no password verifier to replace -> rejected (400).

## Quality notes
- *Auth verifier and unlock wrap both change:* changing the password must update server authentication
  and the client-produced PASSWORD wrap. The server never receives the Master Key.
- *No content re-encryption:* the Master Key and content keys are unchanged.
- *Session hygiene:* all sessions are invalidated because the password credential changed.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, Secret Key, unlock wraps)
- Decisions: [AD-08](../../../decisions/README.md) (Secret Key)
- HTTP client: [uc.account.change-password.http](../../../../../http/features/account/use-cases/uc.account.change-password.http)
- Related use-cases: `uc.account.login`, `uc.account.reset-password`, `uc.keys.upsert-unlock-wrap`
