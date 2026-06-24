# UC: Delete avatar

- **ID:** uc.account.delete-avatar
- **Primary actor:** Memory Curator
- **Goal:** Remove the account profile image.
- **Preconditions:** Authenticated.
- **Postconditions (success):** The account has no current avatar pointer; `UserSummary.avatarUrl` is
  absent; the old avatar object is deleted best-effort.

## Main flow
1. The user removes the profile image in account settings.
2. The client calls `DELETE /api/me/avatar`.
3. The server resolves the session and loads the account.
4. The server clears the avatar object pointer, deletes the old object best-effort if one existed, and
   returns the updated `UserSummary`.

## Alternate / exception flows
- A1: No avatar is set -> the endpoint still returns the current user without an avatar URL.
- E1: Session missing or invalid -> rejected (401).

## Quality notes
- *Idempotent user outcome:* after success, the account has no avatar regardless of whether an old object
  existed.
- *No key impact:* avatar removal changes only profile metadata and object storage, never wrapped keys or
  encrypted content.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (server-visible account metadata)
- Decisions: [AD-31](../../../decisions/0031-profile-fields-server-visible.md)
- Related use-cases: `uc.account.set-avatar`, `uc.account.update-profile`
