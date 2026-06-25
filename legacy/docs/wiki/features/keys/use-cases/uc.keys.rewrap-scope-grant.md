# UC: Rewrap a scope grant

- **ID:** uc.keys.rewrap-scope-grant
- **Primary actor:** Memory Curator
- **Goal:** Replace the actor's own wrapped grant for an existing scope without exposing the scope key
  to the server.
- **Preconditions:** Authenticated; the actor already has a grant for the target scope and can open it
  locally.
- **Postconditions (success):** The stored grant ciphertext for that actor and scope is replaced; the
  scope public key and membership/access semantics are unchanged.

## Main flow
1. The client opens an existing scope grant locally with the user's identity private key.
2. The client wraps the same scope private key to the current identity public key using the active
   crypto suite.
3. The client uploads the replacement wrapped grant for `scopeId`.
4. The server verifies only that the session resolves, the scope exists and the caller already has a
   grant for that scope.
5. The server replaces the caller's grant ciphertext and returns the updated grant metadata.

## Alternate / exception flows
- E1: The session is missing or invalid → rejected.
- E2: The scope does not exist → rejected.
- E3: The caller does not already have a grant for the scope → rejected; this use-case cannot create
  access.
- E4: The replacement grant uses an unknown crypto suite → rejected.

## Quality notes
- *Generic across spaces:* the flow applies to `PERSONAL_ROOT`, `TENANT_ROOT` and future group scopes.
- *No privilege creation:* it updates only `(scopeId, currentUser)` and requires that grant to exist.
- *Blind server:* the server stores opaque replacement ciphertext only; it cannot validate or decrypt
  the wrapped scope private key.

## Related
- Feature: [Keys](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, recovery,
  revocation), [Account keys, devices & recovery](../../../crosscutting/account-key-model.md)
- Related use-cases: `uc.spaces.use-personal`, `uc.account.recover-keys`,
  `uc.admin.create-tenant-invite-owner`
