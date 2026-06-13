# UC: Use the personal space

- **ID:** uc.spaces.use-personal
- **Primary actor:** Memory Curator
- **Goal:** Use the private personal space — maximally confidential, even from the operator.
- **Preconditions:** Authenticated; holds the **personal-space keys** (created at registration).
- **Postconditions (success):** Personal memories are created/curated under the user's own keys;
  unshared content stays confidential from everyone, including the operator.

## Main flow
1. The user selects their **personal space**.
2. The client uses the **personal-space keys** (self-owned, created at registration, **never granted**
   to anyone) to decrypt/encrypt content.
3. The user curates private memories; nothing is shared unless they take an **explicit, informed**
   sharing action.

## Alternate / exception flows
- A1: The user **shares** a personal memory → it becomes "shared" (keys wrapped to recipients) — a
  deliberate, owner-only confidentiality-lowering act.
- E1: Keys unavailable on this device → unlock or recover first.

## Quality notes
- *Paramount invariant in full:* unshared personal content reaches no one — not other users, not the
  operator; **only the owner** lowers it.
- *Self-owned keys:* the personal scope keypair is the user's own; there is no grant and no other
  key-holder.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (paramount invariant, shared vs. unshared),
  [Space Navigation](../../../crosscutting/space-navigation.md) (personal scope context)
- Decisions: [AD-02](../../../decisions/README.md), [AD-04](../../../decisions/README.md) (shared vs. unshared)
- Related use-cases: `uc.spaces.use-group`, `uc.memory.create-tree`
