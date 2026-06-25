# UC: Update profile

- **ID:** uc.account.update-profile
- **Primary actor:** Memory Curator
- **Goal:** Update low-sensitivity, server-visible profile fields for the authenticated account.
- **Preconditions:** Authenticated.
- **Postconditions (success):** The account's display name and username are updated; email,
  credentials, device wraps and content keys are unchanged.

## Main flow
1. The user edits profile metadata in account settings.
2. The client sends the requested display name and username to `PATCH /api/me`.
3. The server resolves the session, trims the display name, validates the username, and checks that the
   username is not held by another account.
4. The server stores the metadata and returns the updated `UserSummary`.
5. Later `GET /api/me` calls return the same updated profile fields.

## Alternate / exception flows
- E1: Session missing or invalid -> rejected (401).
- E2: Display name or username validation fails -> rejected (400).
- E3: The username is already held by another account -> rejected (409).

## Quality notes
- *Server-visible by design:* display name, username, email and avatar pointers are low-sensitivity
  account metadata. Memory titles, captions, journal text, media and sensitive metadata stay encrypted.
- *No key impact:* profile updates never change the Master Key, key wraps, auth verifier or sessions.
- *Separate flows:* email, avatar, password and device operations use their own account use-cases because
  they have different proof, storage and failure semantics.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (server-visible operational metadata)
- Decisions: [AD-31](../../../decisions/0031-profile-fields-server-visible.md)
- HTTP client: [uc.account.update-profile.http](../../../../../http/features/account/use-cases/uc.account.update-profile.http)
- Related use-cases: `uc.account.change-email`, `uc.account.set-avatar`, `uc.account.change-password`
