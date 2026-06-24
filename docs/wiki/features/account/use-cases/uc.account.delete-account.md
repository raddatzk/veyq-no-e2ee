# UC: Delete account

- **ID:** uc.account.delete-account
- **Primary actor:** Memory Curator
- **Goal:** Permanently delete one's own account by credential and key erasure.
- **Preconditions:** Authenticated; re-authenticates with the current password at the point of deletion.
- **Postconditions (success):** Key material and credentials are destroyed; sessions are invalidated;
  the profile is anonymized and made non-login; personal content that only this account could decrypt is
  crypto-erased; shared content persists for remaining key-holders.

## Main flow
1. The user starts account deletion and confirms the irreversible action.
2. The client submits the current password to `DELETE /api/me`.
3. The server resolves the session and verifies the password.
4. The server purges the user's personal scopes, deletes pending password-reset and email-change tokens,
   invalidates all sessions, deletes key blobs and identity keys, and deletes the avatar object best-effort.
5. The server anonymizes the account row, releases email and username by replacing/clearing them, drops
   Server Owner role, marks the row as a system deleted-user account, and records `deletedAt`.
6. The client clears local session and unlock state.

## Alternate / exception flows
- A1: Shared content with other key-holders survives; attribution changes to the deleted-user profile.
- E1: Session missing or invalid -> rejected (401).
- E2: Password verification fails -> deletion is aborted (401).
- E3: The account is the last Server Owner -> rejected (409).

## Quality notes
- *Crypto-erasure:* destroying identity and Master-Key wraps renders personal/unshared content
  unrecoverable even before blob purge completes.
- *Shared content persists:* content that other members can still decrypt is not silently removed from
  them; only the deleted user's identity metadata is anonymized.
- *No escrow:* deletion is irreversible because the server never had plaintext keys.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (deletion & erasure, key hierarchy)
- Decisions: [AD-10](../../../decisions/README.md) (deletion & erasure), [AD-02](../../../decisions/README.md) (E2EE)
- HTTP client: [uc.account.delete-account.http](../../../../../http/features/account/use-cases/uc.account.delete-account.http)
- Related use-cases: `uc.account.login`, `uc.account.recover-keys`
