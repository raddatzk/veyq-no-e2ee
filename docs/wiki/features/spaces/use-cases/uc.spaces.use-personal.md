# UC: Use the personal space

- **ID:** uc.spaces.use-personal
- **Primary actor:** Memory Curator
- **Goal:** Access and use the private personal space — maximally confidential, even from the operator.
- **Preconditions:** Authenticated; has a self-owned **personal-space grant** created during account
  registration.
- **Postconditions (success):** The client can open the user's personal scope locally, provision/use its
  encrypted content key and store encrypted items; unshared
  content remains inaccessible to everyone else, including the operator.

## Main flow
1. The authenticated client requests the user's **personal scope grant**.
2. The server resolves the session and returns only the personal scope public key plus the wrapped
   private key grant for that user.
3. The client opens the personal scope locally with the user's identity key; the server never sees
   personal scope private key material.
4. The client fetches the personal scope's wrapped content key, or provisions it once by wrapping a
   fresh content key to the personal scope public key.
5. The client encrypts personal-space items locally under that content key; the server stores and
   lists only sealed ciphertext.

## Alternate / exception flows
- E1: Keys unavailable on this device → unlock or recover first.
- E2: Session unavailable or invalid → the personal scope grant is not returned.
- E3: A caller without the personal scope grant cannot fetch the content key or list/store items.
- E4: A second content-key provision for the same scope is rejected; clients adopt the existing key.

## Quality notes
- *Paramount invariant in full:* unshared personal content reaches no one — not other users, not the
  operator; **only the owner** lowers it.
- *Self-owned keys:* the personal scope keypair is the user's own; only the owner receives a wrapped
  self-grant.
- *Blind content store:* personal-space content keys are wrapped to the scope and items are sealed under
  that key client-side. The server gates by scope grant, but never sees plaintext or content keys.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (paramount invariant, shared vs. unshared),
  [Space Navigation](../../../crosscutting/space-navigation.md) (personal scope context)
- Decisions: [AD-02](../../../decisions/README.md), [AD-03](../../../decisions/README.md) (content keys),
  [AD-04](../../../decisions/README.md) (shared vs. unshared)
- Related use-cases: `uc.spaces.use-group`, `uc.memory.create-tree`
