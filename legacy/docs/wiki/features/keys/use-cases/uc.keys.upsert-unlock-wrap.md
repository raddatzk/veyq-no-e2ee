# UC: Add or replace an unlock wrap

- **ID:** uc.keys.upsert-unlock-wrap
- **Primary actor:** Memory Curator
- **Goal:** Add or replace one wrapped copy of the Master Key for a supported unlock method without
  exposing the Master Key to the server.
- **Preconditions:** Authenticated; the client has already unlocked the Master Key locally.
- **Postconditions (success):** The server stores the submitted wrapped Master Key blob for the
  requested method and returns the stored wrap.

## Main flow
1. The client unlocks the Master Key locally through any existing valid path.
2. The user configures a new or replacement unlock method, such as password + Secret Key, passkey PRF,
   recovery code or device-local protection.
3. The client derives the method-specific KEK locally and wraps the unchanged Master Key.
4. The client uploads the new `MasterKeyWrap`.
5. The server verifies the session, crypto suite and path/request method match, then stores the opaque
   wrapped blob for the authenticated user.

## Alternate / exception flows
- E1: Session missing or invalid → rejected.
- E2: Path `unlockMethod` does not match the payload's `unlockMethod` → rejected.
- E3: Unknown crypto suite → rejected.
- E4: A wrap for the same method and `credentialRef` already exists → replaced; otherwise inserted.

## Quality notes
- *Blind server:* the server stores ciphertext plus metadata only; it cannot open the Master Key.
- *No content re-encryption:* the Master Key is unchanged. Adding or replacing an unlock method changes
  only one stored wrap.
- *Removal is separate:* deleting wraps must preserve at least one surviving unlock path and is handled
  by a separate use-case.

## Related
- Feature: [Keys](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key recovery, Secret Key,
  unlock wraps), [Account keys, devices & recovery](../../../crosscutting/account-key-model.md)
- Related use-cases: `uc.account.login`, `uc.account.recover-keys`,
  `uc.keys.remove-unlock-method`
