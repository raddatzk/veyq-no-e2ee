# UC: Invite members, grant access & assign roles

- **ID:** uc.admin.invite-members-roles
- **Primary actor:** Tenant Owner
- **Secondary actor:** Invited User
- **Goal:** Invite members (new or existing), grant scope access, assign roles, and remove members.
- **Preconditions:** Authenticated Tenant Owner **holding the relevant scope keys**.
- **Postconditions (success):** Members are invited/granted access to the selected scopes; roles are
  assigned; removed members lose **future** access via revocation + rotation.

## Main flow
1. The owner picks who to add and **which scopes** they get (tenant, specific groups).
2. **New user** → the owner issues a **temp-wrap invite**: the selected scope keys are wrapped to a temp
   public key; the link (token in fragment) goes to the invitee →
   [register-via-invitation](../../account/use-cases/uc.account.register-via-invitation.md).
3. **Existing user** → **direct grant**: the owner wraps the selected scope keys to the user's
   **existing identity public key** (from the key directory) — no new account, no temp-wrap; the
   recipient's client unwraps automatically.
4. The owner assigns a **role** (TenantMember/TenantOwner; group roles). Roles govern membership and
   settings — **content access is the wraps, not the role** (ADR 0005).

## Alternate / exception flows
- A1: **Remove a member** → membership becomes "Removed" (soft, rejoinable — ADR 0017); the grant is
  revoked + the **scope key is rotated** ([AD-24](../../../decisions/README.md)); content & attribution
  are preserved; rejoining re-grants access.
- A2: **Server Owner deactivation** of an account → **login blocked** (auth), keys untouched, reversible
  — a *global* action, distinct from tenant removal.
- A3: Change an existing member's role.
- E1: The owner doesn't hold the scope keys → granting is blocked (you can only grant what you can read).

## Quality notes
- *Invite vs. grant:* new users bootstrap via a **temp-wrap**; existing users via a **direct wrap** to
  their public key — same access, different mechanism.
- *Role ≠ access (ADR 0005):* roles govern membership/settings; the set of decryptable scopes governs
  content.
- *Removal = two-layer revocation ([AD-24](../../../decisions/README.md)):* instant token/access
  invalidation + eventual key rotation; already-downloaded content cannot be retracted.
- *Key-directory trust:* granting to an existing user relies on their published public key
  (TOFU/KT, [AD-07](../../../decisions/README.md)).

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Tenant Owner](../../../actors.md), [Invited User](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (provisioning, revocation,
  key-directory trust)
- Decisions: [AD-21](../../../decisions/README.md) (onboarding), [AD-24](../../../decisions/README.md) (revocation), [AD-07](../../../decisions/README.md) (key-directory trust); ADR 0005, ADR 0017 (legacy)
- Related use-cases: `uc.account.register-via-invitation`, `uc.spaces.use-group`, `uc.account.erase-account`
