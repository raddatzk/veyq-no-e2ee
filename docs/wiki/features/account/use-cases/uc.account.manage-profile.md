# UC: Manage profile & credentials

> **Status: Partial.** Implemented today: view the authenticated user and update the server-visible
> display name. Email changes, avatar/locale fields, credential changes and trusted-device management
> remain open.

- **ID:** uc.account.manage-profile
- **Primary actor:** Memory Curator
- **Goal:** Manage profile fields and account credentials/devices.
- **Preconditions:** Authenticated (with key access for credential changes that re-wrap the Master Key).
- **Postconditions (success):** Profile fields are updated; credentials/devices reflect the changes;
  credential changes adjust **Master-Key wraps without re-encrypting content**.

## Current scope
The web client exposes profile editing in Settings. The backend accepts `PATCH /api/me/profile` for the
authenticated user's **display name** only. The field is low-sensitivity identity metadata and is stored
server-side; content names, Memory data and keys remain encrypted and unchanged.

## Main flow
1. The user edits **profile fields** — currently display name; first/last name, avatar and locale are
   deferred.
2. The user changes **email** → a verification token is sent to the new address; the change applies on
   confirmation.
3. The user manages **credentials**: add/remove a **passkey** (adds/removes a PRF unlock wrap), or change
   the **password** (recomputes the password + Secret Key wrap) — **no content re-encryption**.
4. The user manages **devices**: lists trusted devices and **deauthorizes** one (removes its device
   wrap — see [login](uc.account.login.md) A4, [AD-24](../../../decisions/README.md)).

## Alternate / exception flows
- A1: Enable/disable TOTP → see [enable-totp](uc.account.enable-totp.md).
- E1: Email already in use / verification expired → rejected.
- E2: Removing the **last** Master-Key-capable method is blocked → rejected (must keep at least one).

## Quality notes
- *Identity fields server-visible:* first/last/display name, avatar, email and locale are
  **low-sensitivity identity metadata, server-visible** (consistent with membership being visible);
  content stays encrypted. Per-scope encryption of the real name is a possible later hardening.
- *Implemented profile edit:* the display name is trimmed and validated; updating it changes only the
  account row and the user's current `UserSummary`, never any content key or wrapped secret.
- *Credential changes = wrap changes:* adding/removing a method adds/removes a Master-Key **wrap**; the
  Master Key and content are unchanged ("one Master Key, many wraps").
- *Keep-one-method invariant:* at least one Master-Key-capable method must remain — password + Secret
  Key is always capable; a passkey only **with PRF** (mirrors [register](uc.account.register-via-invitation.md) A3).

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, wraps, revocation),
  [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-08](../../../decisions/README.md) (Secret Key), [AD-24](../../../decisions/README.md) (revocation),
  [AD-23](../../../decisions/README.md) (capability/PRF), [AD-31](../../../decisions/0031-profile-fields-server-visible.md)
- Related use-cases: `uc.account.login`, `uc.account.enable-totp`, `uc.account.recover-keys`
