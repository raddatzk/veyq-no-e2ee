# ADR 0006: Public Memory Sharing

## Status

Accepted

## Context

veyq needs shareable Memory subtrees without turning public pages into tenant membership or social graph surfaces. Public shares must be revocable, optionally password-protected and scoped to the selected Memory tree.

## Decision

Public Memory shares are separate resources with their own roles, anonymous identities and share-scoped token sessions.

- A share points at one root Memory and grants access to that Memory subtree only.
- Public share links use `/s/{shareId}`. Password protection is optional.
- Passwordless `VIEWER` shares can be opened without an anonymous identity. Passwordless write/upload roles still require an anonymous share identity for attribution.
- Shares can have an optional internal management name. This name is never exposed in public share responses.
- Shares are revoked by setting `enabled=false`. Managed share deletion is intentionally out of scope for V1.
- Share passwords are stored as bcrypt hashes.
- Successful public login creates an anonymous share identity with its own display name and dedicated HttpOnly share access-/refresh-token cookies. Share tokens are separate from user auth tokens but follow the same rotation and revocation model.
- V1 public roles are `VIEWER`, `CONTRIBUTOR`, `MANAGER` and `UPLOADER`.
- `VIEWER` receives the normal read-only Memory view for the shared subtree. `CONTRIBUTOR` may create and edit Memory metadata, covers and media metadata inside the subtree, but cannot delete. `MANAGER` may additionally delete media and submemories inside the subtree, but cannot delete the share root. `UPLOADER` remains upload-only and only sees its own uploads.
- Anonymous uploads are attributed to the share identity, not to a short-lived access-token session. Media responses generate uploader display names dynamically from either the registered user or the share identity.
- Media ownership is defined by tenant, scope and Memory. `veyq_media.owner_user_id` is not an audit source for uploads.

## Consequences

Existing authenticated Memory APIs remain protected by tenant membership. Public share APIs resolve access through share state and session state instead of relaxing tenant filters. Public responses must not expose internal database IDs, internal share names or cross-Memory person graphs. Protected unauthenticated share summaries intentionally omit Memory metadata until access is established.

Share removal is deferred because anonymous uploads are attributed through `share_identity_id`. A hard delete of a share would remove identities and break historical uploader display names for existing media. If share removal is added later, it must be modeled as a soft delete, for example with `deleted_at`, and both management and public queries must filter deleted shares.
