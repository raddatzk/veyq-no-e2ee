# UC: Create a tenant and invite its owner

- **ID:** uc.admin.create-tenant-invite-owner
- **Primary actor:** Server Owner
- **Secondary actor:** Tenant Owner
- **Goal:** Create a tenant and invite its owner — **without the operator ever holding the tenant's keys**.
- **Preconditions:** Authenticated Server Owner; the instance is set up.
- **Postconditions (success):** A tenant **shell** exists (metadata, domain); an owner-claim invitation
  is issued; the Tenant Owner becomes the **first key-holder** on claim; the operator holds no tenant
  content keys.

## Main flow
1. The Server Owner creates a **tenant shell** — name, display name, domain, branding placeholder —
   **metadata only, no content keys**.
2. They issue an **owner invitation** to a target email address. The backend sends the single-use
   bootstrap link to that address and does **not** return the bootstrap secret to the provisioning
   client. The tenant is empty, so there are **no scope keys to temp-wrap** — this is an
   **ownership claim**.
3. The invitee accepts (→ [register-via-invitation](../../account/use-cases/uc.account.register-via-invitation.md)
   if new, or claims as an existing user). The backend requires the registering/claiming account email
   to match the invitation target email; their client **generates the tenant scope keypair** and they become
   its **first key-holder**.
4. The tenant is **activated** with a Tenant Owner.

## Alternate / exception flows
- A1: The owner is an **existing user** → they claim ownership and generate the tenant scope keypair
  under their existing account (no new account).
- A2: After activation, the Tenant Owner can schedule tenant deletion, cancel it during the grace
  period, or let the garbage collector hard-purge the tenant after the deadline.
- E1: The invite is expired/revoked → rejected.
- E2: A different account email tries to claim/register with the owner invite → rejected.

## Quality notes
- *Operator never holds tenant keys:* the Server Owner provisions only the **shell**; the Tenant Owner
  is the first key-holder — the paramount invariant holds even for tenant content vs. the operator.
- *Empty-tenant invite = ownership claim:* no temp-wrap of content; the tenant's content keys are born
  when the owner claims.
- *No immediate self-claim by the provisioning client:* the Server Owner can create the shell and trigger
  delivery, but cannot activate the tenant unless they control the target inbox and accept that invite as
  the target account.
- *Tenant deletion is owner-controlled:* deleting a tenant is not account deletion. It is scheduled,
  cancellable during the grace period, and finally purged by the tenant lifecycle garbage collector.

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Server Owner](../../../actors.md), [Tenant Owner](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (key provisioning, key hierarchy)
- Decisions: [AD-02](../../../decisions/README.md) (E2EE), [AD-21](../../../decisions/README.md) (onboarding)
- Related use-cases: `uc.account.register-via-invitation`, `uc.admin.invite-members-roles`
