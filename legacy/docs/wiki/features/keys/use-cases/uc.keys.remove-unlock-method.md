# UC: Remove an unlock method

- **ID:** uc.keys.remove-unlock-method
- **Primary actor:** Memory Curator
- **Goal:** Remove a stored Master Key unlock wrap that should no longer be usable.
- **Preconditions:** Authenticated; removal keeps the mandatory **PASSWORD** wrap (the hardware-independent
  Master-Key-capable recovery path) and at least one stored unlock wrap.
- **Postconditions (success):** The selected wrapped Master Key blob is deleted; other unlock paths and
  encrypted content remain unchanged.

## Main flow
1. The user chooses an unlock method or enrolled credential to remove, such as a device or passkey.
2. The client calls the server with the unlock method and, when needed, a `credentialRef`.
3. The server resolves the session and finds only the authenticated user's matching wrap.
4. The server verifies the removal keeps the mandatory PASSWORD wrap and at least one stored wrap.
5. The server deletes the selected opaque wrap.

## Alternate / exception flows
- E1: Session missing or invalid → rejected.
- E2: The selected wrap does not exist for this user → rejected.
- E3: The selected wrap is the user's last PASSWORD wrap → rejected (`LAST_CAPABLE_METHOD`): the blind
  server can't verify passkey PRF capability, so it keeps the one method guaranteed to be Master-Key-capable.
- E4: The selected wrap is the user's final stored wrap → rejected (`LAST_UNLOCK_METHOD`).
- E5: Multiple credentials exist for the same method → `credentialRef` selects the intended one.

## Quality notes
- *No content re-encryption:* deleting an unlock wrap does not rotate the Master Key or content.
- *Lockout guard:* the server always keeps the mandatory PASSWORD wrap (and never drops below one stored
  wrap) — hardware-bound methods (passkey, device) can all be lost, so the durable recovery path must stay.
- *Own account only:* the endpoint cannot delete another user's wrap.

## Related
- Feature: [Keys](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key recovery, unlock wraps),
  [Account keys, devices & recovery](../../../crosscutting/account-key-model.md)
- Related use-cases: `uc.keys.upsert-unlock-wrap`, `uc.account.recover-keys`
