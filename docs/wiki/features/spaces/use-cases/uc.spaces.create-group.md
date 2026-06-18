# UC: Create & administer a group

- **ID:** uc.spaces.create-group
- **Primary actor:** Group Owner
- **Secondary actor:** Invited User
- **Goal:** Create a collaborative **group** — a shared scope with its own content key and a **sealed
  name** — invite tenant members into it, and manage their roles, all operator-blind.
- **Preconditions:** An authenticated, **ACTIVE tenant member** (who becomes the group's first owner);
  invitees are ACTIVE members of the same tenant.
- **Postconditions (success):** A `GROUP_ROOT` scope exists with a sealed name; the creator holds a
  scope grant and the **GroupOwner** role; invited members receive the scope key **re-wrapped to their
  identity**; the operator sees only the membership graph, the sealed name and wrapped keys.

## Access = the wrap
Holding the **group scope key** *is* membership; there is no separate per-action permission. *Using* a
group once joined is [use-group](uc.spaces.use-group.md); this UC is about **creating** and
**administering** one.

## Main flow
1. The creator generates a group **scope keypair** and a 32-byte **content key** client-side, seals the
   group **name** under the content key, wraps the scope private key to their **own identity**, and wraps
   the content key to the scope public key.
2. The creator posts these artifacts (`POST /api/tenants/{tenantId}/groups`); the server stores the
   scope, a **grant** for the creator, the wrapped content key and a **GroupOwner** membership row.
3. The creator **invites** a member (shared member-invite mechanism): the scope key is wrapped to a
   one-time **temp key**; the link carries the invite id + bootstrap secret + temp key in its fragment
   (pragmatic), or the temp key **encrypted under a passphrase** (operator-blind).
4. The invited member opens the link, recovers the temp key (entering the passphrase if protected) and
   **accepts**; the server **re-wraps** the scope key to the joiner's identity and adds a **GroupMember**
   row.
5. The joiner now sees the group, unwraps the scope + content key, and decrypts its sealed **name** and
   content.
6. The owner **manages** membership — promote/demote roles, remove members, or delete the group.

## Alternate / exception flows
- A1: The owner promotes a member to **GroupOwner** → accepted, provided at least one owner remains.
- E1: A **non-tenant-member** creates or is added → rejected (must be an ACTIVE tenant member).
- E2: **Adding an existing member** again → `409` (already a member).
- E3: A **non-owner** tries to manage membership → `403` (GroupOwner required).
- E4: A **wrong passphrase** on a protected invite → the temp-key unwrap fails client-side; no access.
- E5: Demoting or removing the **last owner** → rejected (a group keeps ≥ 1 owner).
- E6: A **removed tenant member** → their grant and membership rows are purged; membership checks block
  any stale access.

## Quality notes
- *Confidentiality (paramount):* the operator sees the **sealed name**, the membership graph and roles
  (plaintext by design) and wrapped keys — never the group name, content key or scope private key.
- *Operator-blind invites:* the passphrase-protected link keeps even the **temp key** out of the
  operator's reach.
- *Shared, not secret:* group content is **confidential from the operator** but intentionally shared
  among members.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Group Owner](../../../actors.md),
  [Invited User](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (scope keys, grants, sealed names),
  [Space Navigation](../../../crosscutting/space-navigation.md)
- Decisions: [AD-02](../../../decisions/README.md), [AD-03](../../../decisions/README.md) (scopes &
  keys); ADR 0005 (access = the wrap)
- Related use-cases: `uc.spaces.use-group`, `uc.admin.invite-members-roles`
