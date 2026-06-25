# UC: Manage login sessions

- **ID:** uc.account.manage-login-sessions
- **Primary actor:** Memory Curator
- **Goal:** Review, rename and revoke active login-session families for the account.
- **Preconditions:** Authenticated. For human-readable labels, the client has the Master Key unlocked so
  it can encrypt and decrypt login-session label metadata locally.
- **Postconditions (success):** Login-session families reflect the renamed encrypted metadata, or the
  selected root session family is revoked.

## Main flow
1. The user opens the session list.
2. The client calls `GET /api/me/login-sessions`.
3. The server returns one active entry per `rootSessionId`. Refresh rotation rows remain internal audit
   history and are not shown as separate user-facing sessions.
4. The client decrypts session labels locally for display.
5. The user renames a session; the client encrypts the new label and calls
   `PUT /api/me/login-sessions/{rootSessionId}`.
6. The server stores the encrypted label for the authenticated user's matching session family.
7. The user removes a session; the client calls `DELETE /api/me/login-sessions/{rootSessionId}`.
8. The server revokes all rows in that root session family.

## Alternate / exception flows
- A1: Label decryption fails -> the client falls back to a generic local device/browser label.
- E1: Session missing or invalid -> rejected (401).
- E2: Encrypted label is empty or too large -> rejected (400).
- E3: Removing the current session invalidates the current session; the client returns to login.

## Quality notes
- *Label confidentiality:* human-readable labels are client-encrypted opaque text. The server can route
  and revoke sessions without learning labels.
- *Refresh history:* each refresh creates a child row under the root session; reuse of a rotated refresh
  token revokes the family.
- *Per-account scope:* a user can list, rename and remove only their own login sessions.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy), [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-24](../../../decisions/README.md) (revocation)
- Related use-cases: `uc.account.login`, `uc.keys.remove-unlock-method`
