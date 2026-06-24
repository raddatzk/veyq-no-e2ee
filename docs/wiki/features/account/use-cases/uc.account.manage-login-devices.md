# UC: Manage login devices

- **ID:** uc.account.manage-login-devices
- **Primary actor:** Memory Curator
- **Goal:** Review, rename and deauthorize active login devices for the account.
- **Preconditions:** Authenticated. For human-readable names, the client has the Master Key unlocked so
  it can encrypt and decrypt device-name metadata locally.
- **Postconditions (success):** Login-device rows reflect the renamed encrypted metadata, or all sessions
  for a removed device identifier are invalidated.

## Main flow
1. The user opens the device list.
2. The client calls `GET /api/me/devices`.
3. The server groups active sessions by `deviceRef` and returns the latest login timestamp plus the
   encrypted device name for each device.
4. The client decrypts device names locally for display.
5. The user renames a device; the client encrypts the new name and calls `PUT /api/me/devices/{deviceRef}`.
6. The server stores the encrypted name for the authenticated user's matching sessions.
7. The user removes a device; the client calls `DELETE /api/me/devices/{deviceRef}`.
8. The server invalidates all active sessions for that device identifier.

## Alternate / exception flows
- A1: Device-name decryption fails -> the client falls back to a generic local device label.
- E1: Session missing or invalid -> rejected (401).
- E2: Encrypted device name is empty or too large -> rejected (400).
- E3: Removing the current device invalidates the current session; the client returns to login.

## Quality notes
- *Name confidentiality:* human-readable device names are client-encrypted opaque text. The server can
  route and invalidate sessions without learning device labels.
- *Session revocation only:* removing a login device invalidates server sessions for that `deviceRef`.
  Unlock-wrap removal is handled by the Keys feature.
- *Per-account scope:* a user can list, rename and remove only their own login devices.

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy), [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-24](../../../decisions/README.md) (revocation)
- Related use-cases: `uc.account.login`, `uc.keys.remove-unlock-method`
