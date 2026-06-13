# UC: Use the personal space

- **ID:** uc.spaces.use-personal
- **Primary actor:** Memory Curator
- **Goal:** Access the private personal space key material — maximally confidential, even from the operator.
- **Preconditions:** Authenticated; has a self-owned **personal-space grant** created during account
  registration.
- **Postconditions (success):** The client can open the user's personal scope locally; unshared
  content remains inaccessible to everyone else, including the operator.

## Main flow
1. The authenticated client requests the user's **personal scope grant**.
2. The server resolves the session and returns only the personal scope public key plus the wrapped
   private key grant for that user.
3. The client opens the personal scope locally with the user's identity key; the server never sees
   personal scope private key material.

## Alternate / exception flows
- E1: Keys unavailable on this device → unlock or recover first.
- E2: Session unavailable or invalid → the personal scope grant is not returned.

## Quality notes
- *Paramount invariant in full:* unshared personal content reaches no one — not other users, not the
  operator; **only the owner** lowers it.
- *Self-owned keys:* the personal scope keypair is the user's own; only the owner receives a wrapped
  self-grant.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (paramount invariant, shared vs. unshared),
  [Space Navigation](../../../crosscutting/space-navigation.md) (personal scope context)
- Decisions: [AD-02](../../../decisions/README.md), [AD-04](../../../decisions/README.md) (shared vs. unshared)
- Related use-cases: `uc.spaces.use-group`, `uc.memory.create-tree`
