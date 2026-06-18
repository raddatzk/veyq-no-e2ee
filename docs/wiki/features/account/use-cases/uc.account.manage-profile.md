# UC: Manage profile & credentials

> **Status: Planned — not yet implemented.** Documented ahead of the code; no coverage file by design.

- **ID:** uc.account.manage-profile
- **Primary actor:** Memory Curator
- **Goal:** Manage profile fields and account credentials/devices.
- **Preconditions:** Authenticated (with key access for credential changes that re-wrap the Master Key).
- **Postconditions (success):** Profile fields are updated; credentials/devices reflect the changes;
  credential changes adjust **Master-Key wraps without re-encrypting content**.

## Main flow
1. The user edits **profile fields** — first name, last name, display name, avatar, locale.
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
- *Credential changes = wrap changes:* adding/removing a method adds/removes a Master-Key **wrap**; the
  Master Key and content are unchanged ("one Master Key, many wraps").
- *Keep-one-method invariant:* at least one Master-Key-capable method must remain — password + Secret
  Key is always capable; a passkey only **with PRF** (mirrors [register](uc.account.register-via-invitation.md) A3).

## Related
- Feature: [Account & Auth](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, wraps, revocation),
  [Platform Concepts](../../../crosscutting/platform-concepts.md) (auth & sessions)
- Decisions: [AD-08](../../../decisions/README.md) (Secret Key), [AD-24](../../../decisions/README.md) (revocation), [AD-23](../../../decisions/README.md) (capability/PRF)
- Related use-cases: `uc.account.login`, `uc.account.enable-totp`, `uc.account.recover-keys`
