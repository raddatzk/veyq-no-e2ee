# UC: Use a group space

- **ID:** uc.spaces.use-group
- **Primary actor:** Memory Curator
- **Goal:** Switch into a group space and collaborate on its shared content.
- **Preconditions:** Member of the group; the client holds the **group scope key** (wrapped to the user
  when granted).
- **Postconditions (success):** The group's content is decryptable and editable per visibility;
  collaboration (journal) works.

## Main flow
1. The user selects a **group** they belong to.
2. The client uses the **group scope key** (its scope-private key was wrapped to the user on grant) to
   decrypt the group's content keys → its content.
3. The user creates/curates **shared** memories; **any member can manage visible content** (ADR 0005);
   journals collaborate live.

## Alternate / exception flows
- A1: **Just granted access** (existing user) → the group appears once the grant's wrap is fetched; no
  user action needed.
- E1: Not a member / no grant → the group isn't visible.

## Quality notes
- *Access = the wrap:* being "in" a group means holding its scope key; there is **no** separate
  per-action content permission (visibility = access, ADR 0005).
- *Shared scope:* content here is **shared** — confidential from the operator, intentionally shared
  among members.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (scope keys, shared vs. unshared),
  [Space Navigation](../../../crosscutting/space-navigation.md) (group scope context),
  [Live Collaboration](../../../crosscutting/live-collaboration.md)
- Decisions: [AD-02](../../../decisions/README.md), [AD-03](../../../decisions/README.md) (scopes & keys); ADR 0005 (legacy)
- Related use-cases: `uc.admin.invite-members-roles`, `uc.memory.create-tree`
