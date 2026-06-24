# UC: Set avatar

- **ID:** uc.account.set-avatar
- **Primary actor:** Memory Curator
- **Goal:** Set or replace the account profile image.
- **Preconditions:** Authenticated; the selected image uses a supported avatar content type and is within
  the configured byte-size limit.
- **Postconditions (success):** The new avatar object becomes current; the updated user summary contains a
  fresh short-lived `avatarUrl`; the previous avatar object is deleted best-effort.

## Main flow
1. The user chooses a profile image.
2. The client requests an upload ticket from `POST /api/me/avatar/upload` with content type and byte size.
3. The server validates metadata, creates a server-owned profile-avatar object key, and returns a
   short-lived presigned PUT URL plus opaque `uploadRef`.
4. The client uploads the image bytes directly to the object store.
5. The client confirms with `POST /api/me/avatar/upload/complete`.
6. The server verifies that the generated object exists, rechecks the size limit, stores the avatar object
   pointer, deletes the previous avatar object best-effort, and returns the updated `UserSummary`.

## Alternate / exception flows
- E1: Session missing or invalid -> rejected (401).
- E2: Unsupported content type, empty file or oversized file -> rejected (400).
- E3: The object store cannot issue presigned upload URLs -> rejected (409).
- E4: The completion `uploadRef` is not owned by this account or is malformed -> rejected (400).
- E5: The uploaded object is missing when confirmed -> rejected (404).

## Quality notes
- *Profile image is account metadata:* avatars are server-hosted profile metadata, not encrypted Memory
  media. They never grant content access.
- *Server-owned object key:* the client submits only metadata; the backend chooses the object key so one
  user cannot attach another user's object.
- *Short-lived URLs:* upload and returned avatar URLs are time-boxed presigned URLs.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (server-visible account metadata)
- Decisions: [AD-31](../../../decisions/0031-profile-fields-server-visible.md)
- Related use-cases: `uc.account.update-profile`, `uc.account.delete-avatar`
