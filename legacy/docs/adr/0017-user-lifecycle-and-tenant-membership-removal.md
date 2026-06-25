# ADR 0017: User Lifecycle and Tenant Membership Removal

## Status

Accepted

## Context

veyq uses global user accounts across one or more tenants. Users can create Memories, upload media, appear as Memory participants, own personal-scope content, belong to tenant groups and create public shares.

Account deletion and tenant removal need different semantics. A user who leaves a tenant may later rejoin and should keep historical attribution and ownership of their uploaded media. A user who deletes their global account should release their email address and no longer be visible as an active person, while shared Memories and tenant-visible media should keep working for other authorized users.

Memory and folder access is scope-based. `owner_user_id` fields on Memories and folders are treated as creator or integrity references, not product ownership. The product owner of a Memory or folder is its tenant, group or user scope.

## Decision

veyq will separate global user deletion, global user deactivation and tenant membership removal.

Global account deletion is self-service only and requires fresh re-authentication with either the current password or a passkey assertion. ServerOwners do not delete user accounts through the administrative UI.

When a user deletes their account:

- veyq releases the user's email address for future reuse.
- veyq stores account lifecycle audit fields such as `deletedAt` and `deletedReplacementUserId`.
- veyq revokes active sessions and removes or invalidates account credentials, including passkeys, password reset tokens, email-change tokens, passkey link tokens and mobile handoff state.
- veyq deletes user-private state that is not visible to others, including favorites, capability acknowledgements, avatar files and global profile surfaces.
- veyq deletes user-scoped Memories, folders, media, tracks and playlists because they belong to the user's personal scope.
- veyq removes Memory participant rows for the deleted user.
- veyq transfers tenant-visible attribution and creator references to one global system user representing deleted accounts. This includes uploaded media attribution, media bucket attribution, public share creator references, playlist creator references, participant `addedBy` references and technical creator/integrity references such as Memory or folder `owner_user_id`.
- The anonymized replacement user is not login-capable and must not appear as an invite target, searchable person or active tenant member.

Global user deactivation is a ServerOwner action only. Deactivated users remain identifiable to administrators and tenant owners, but cannot log in, refresh sessions, use passkeys, reset credentials or use existing active sessions. Tenant user lists expose deactivated users with a `Disabled` status where management context requires it. Deactivation is reversible by a ServerOwner.

Tenant membership removal is a soft remove. Removing a user from a tenant does not delete or anonymize their global account, media, buckets, Memory participants or creator references. The membership remains as removed lifecycle state so the same user can later rejoin and regain access to their existing tenant-visible content according to normal Memory and scope permissions. Only active tenant memberships grant tenant access and tenant/group authority.

At least one active ServerOwner must remain. A ServerOwner cannot deactivate the last active ServerOwner, and a deleting user cannot delete the last active ServerOwner account. A tenant must keep at least one active TenantOwner when membership removal or account deletion would otherwise remove the last owner.

## Consequences

Self-service deletion can satisfy privacy expectations without damaging shared Memories for other users. Visible historical content remains coherent, but no longer exposes the deleted account's email, login credentials or personal profile.

Tenant removal becomes reversible and does not require media or bucket migration. Rejoining a tenant can reactivate the previous membership instead of creating a new conceptual relationship.

Authorization checks must consistently treat only active memberships as access-granting. User search, invite flows, participant candidate lists and passkey flows must exclude deleted, disabled and removed users where they are not legitimate management subjects.

The database needs explicit user and membership lifecycle fields. Existing code that currently uses `acceptedAt == null` as pending and `acceptedAt != null` as active must be migrated to a status-aware model.
