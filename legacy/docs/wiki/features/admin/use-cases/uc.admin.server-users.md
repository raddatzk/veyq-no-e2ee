# UC: Manage server users & roles

- **ID:** uc.admin.server-users
- **Primary actor:** Server Owner
- **Goal:** View the instance-wide user roster and promote/demote a member's **server role**, while always
  keeping at least one Server Owner.
- **Preconditions:** Authenticated **Server Owner** (the global server administrator).
- **Postconditions (success):** The roster of non-system accounts is returned, or a target user's server
  role is changed; the instance always retains **≥ 1 Server Owner**. Only low-sensitivity account fields are
  read — never keys or content (operator-blind).

## Main flow
1. The Server Owner lists the **roster** — the instance's non-system accounts (id, email, display name,
   server role), sorted by display name.
2. The Server Owner sets a target user's **server role** (promote a member to Server Owner, or demote an
   owner back to member).
3. The change is persisted and the updated `UserSummary` is returned.

## Alternate / exception flows
- A1: A demotion is allowed once the instance has **more than one** Server Owner.
- E1: A **non-owner** caller is forbidden from listing the roster or setting roles → rejected (403).
- E2: Setting the role of an **unknown user** → rejected (404).
- E3: Demoting the **last** Server Owner is blocked to preserve the keep-one-owner invariant → rejected (409).
- E4: **System accounts** cannot be modified → rejected (403).

## Quality notes
- *Operator-blind roster:* only low-sensitivity identity fields (id, email, display name, server role) are
  read; the operator never sees keys or content.
- *Server role vs tenant role:* the **server** role is a global administrative capability (set at setup for
  the first owner, via invites for members); it is distinct from tenant/group roles, which govern membership
  within a tenant — see [invite-members-roles](uc.admin.invite-members-roles.md).
- *Keep-one-owner invariant:* the instance must always retain at least one Server Owner; the last owner
  cannot be demoted.

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Server Owner](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (operator-blind, roles),
  [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-16](../../../decisions/README.md) (internal API)
- Related use-cases: `uc.admin.setup-server`, `uc.admin.invite-members-roles`
