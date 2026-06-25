# UC: First-run server setup

- **ID:** uc.admin.setup-server
- **Primary actor:** Server Owner
- **Goal:** Bootstrap a fresh instance — create the first account (Server Owner) and base server config.
- **Preconditions:** A freshly installed instance with setup **not yet completed**.
- **Postconditions (success):** The Server Owner account exists with its own keys + Emergency Kit; base
  config (domain, instance name) is set; the instance is marked set up; the operator holds **no** content
  keys.

## Main flow
1. On first run the setup page opens (available **only** while setup is incomplete).
2. The Server Owner sets base config — **base domain**, instance name.
3. They create the first account: an auth method (**password + Secret Key** and/or **passkey**) and a
   display name.
4. The client generates the identity keypair + Master Key + Secret Key **cold** (no invite, no
   temp-wrap), creates the **personal-space** keys, and shows the **Emergency Kit** with forced backup.
5. The client uploads the public key + wrapped blobs + auth verifier; the server stores ciphertext only
   and **marks setup complete**.

## Alternate / exception flows
- A1: After setup, the setup endpoint is **closed** — no second bootstrap.
- E1: The Emergency Kit backup is not confirmed → setup is **not finalized**.

## Quality notes
- *Operator-blind from account #1:* even the Server Owner's content is E2E; the server never holds keys.
- *Cold key birth:* unlike [register-via-invitation](../../account/use-cases/uc.account.register-via-invitation.md)
  there is **no temp-wrap** (nothing to import) — the first user is the root of their own key tree.
- *Tenant creation is separate* — see [create-tenant-invite-owner](uc.admin.create-tenant-invite-owner.md).

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Server Owner](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key hierarchy, onboarding),
  [Platform Concepts](../../../crosscutting/platform-concepts.md)
- Decisions: [AD-02](../../../decisions/README.md) (E2EE), [AD-08](../../../decisions/README.md) (Secret Key), [AD-06](../../../decisions/README.md) (recovery)
- Related use-cases: `uc.account.register-via-invitation`, `uc.admin.create-tenant-invite-owner`
