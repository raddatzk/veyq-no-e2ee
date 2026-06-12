# UC: Erase account

- **ID:** uc.account.erase-account
- **Primary actor:** Memory Curator
- **Goal:** Permanently delete one's own account by **crypto-erasure** (per ADR 0017).
- **Preconditions:** Authenticated; re-authenticates at the point of deletion.
- **Postconditions (success):** Key material destroyed → personal/unshared content unrecoverable;
  shared content persists for remaining key-holders; public attribution reassigned to the system
  "deleted user"; email + permalink released; sessions/credentials revoked; account non-login and
  non-discoverable.

## Main flow
1. The user requests deletion and **re-authenticates** (password / passkey).
2. The user confirms the **irreversible** action (explicit, informed).
3. The server **revokes all sessions and credentials**, **destroys the key material** (Master-Key wraps,
   identity-key wraps), deletes user-private data, and **releases the email + permalink**.
4. Public attribution (e.g. in shared scopes) is **reassigned to the system "deleted user"**.
5. The account becomes **non-login and non-discoverable**.

## Alternate / exception flows
- A1: Content **only the deleting user could decrypt** (personal, or a scope where no other member
  holds the key) is **crypto-erased** with their keys; shared content with **other** key-holders
  survives (attribution only changes).
- E1: Re-authentication fails → deletion aborted.

## Quality notes
- *Crypto-erasure:* destroying the key material renders personal/unshared content unrecoverable
  **immediately**, even before blob purge — a strong, fast deletion guarantee the blind server can honor.
- *Shared content persists:* scope-keyed content remains for members who hold the scope key; only
  attribution changes.
- *Scope distinction (ADR 0017):* this is **self global deletion**; ServerOwner **deactivation** and
  **tenant-membership removal** are separate (admin / spaces).
- *Irreversible, no escrow.*

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (deletion & erasure, key hierarchy)
- Decisions: [AD-10](../../../decisions/README.md) (deletion & erasure), [AD-02](../../../decisions/README.md) (E2EE); legacy ADR 0017 (user lifecycle)
- Related use-cases: `uc.account.login`, `uc.account.recover-keys`
