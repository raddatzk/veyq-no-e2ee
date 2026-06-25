# UC: Rotate the account identity key

- **ID:** uc.keys.rotate-identity
- **Primary actor:** Memory Curator
- **Goal:** Replace the account's identity keypair (e.g. after suspected compromise) without losing
  access to any scope, and without exposing a private key to the server.
- **Preconditions:** Authenticated with the master key unlocked; holds grants for some scopes (at least
  the personal scope created at registration).
- **Postconditions (success):** A new identity keypair is in the key directory; the new private key is
  wrapped under the **same master key** (unlock methods unchanged); every scope grant the user held is
  re-wrapped to the new identity public key; the old identity public key no longer opens anything.

## Main flow
1. The client generates a **new identity keypair** (hybrid KEM + signing) and wraps its private bundle
   under the unlocked master key (the master key and its unlock wraps do not change).
2. The client lists **all the user's grants** (`GET /api/me/grants`): each carries the scope id, kind,
   scope public key and the grant ciphertext.
3. For each grant the client **opens** the scope private key with the *old* identity private key and
   **re-wraps** it to the *new* identity public key, using the scope's context (`personal-scope-grant`
   for the personal scope, `scope-grant` for shared scopes).
4. The client uploads the new identity public key, the new wrapped identity private key and the
   re-wrapped grants in one request (`POST /api/me/identity/rotate`).
5. The server verifies the session and that the re-wrapped grants cover **exactly** the user's current
   grants, then atomically swaps the identity public key + wrapped private key and replaces every grant.

## Alternate / exception flows
- E1: The session is missing or invalid → rejected.
- E2: The re-wrapped grants do not cover exactly the user's current grants (one missing, or one for a
  scope they do not hold) → rejected, so rotation cannot drop or invent access.
- E3: An unknown crypto suite on the identity key or any grant → rejected.

## Quality notes
- *Master key unchanged:* only the identity keypair rotates; unlock wraps and the auth verifier are
  untouched, so login still works and re-keys nothing else.
- *No content re-encryption:* scope keys and content keys are unchanged — only their wrapping to the
  identity moves. The set of decryptable scopes is preserved exactly.
- *Blind server:* the server stores opaque ciphertext + public keys only; it never sees a private key.

## Related
- Feature: [Keys](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, recovery),
  [Account keys, devices & recovery](../../../crosscutting/account-key-model.md)
- Related use-cases: `uc.keys.rewrap-scope-grant`, `uc.account.recover-keys`, `uc.spaces.use-personal`
