# UC: Invite members, grant access & assign roles

- **ID:** uc.admin.invite-members-roles
- **Primary actor:** Tenant Owner
- **Secondary actor:** Invited User
- **Goal:** Invite members (new or existing), grant scope access, assign roles, and remove members.
- **Preconditions:** Authenticated Tenant Owner **holding the relevant scope keys**.
- **Postconditions (success):** Members are invited/granted access to the selected scopes; roles are
  assigned; removed members lose **future** access via revocation + rotation.

## Main flow
1. The owner picks who to add and **which scopes** they get (tenant, specific groups), and a **role**.
2. The owner issues a **temp-wrap invite**: the selected scope keys are wrapped to a one-time temp public
   key; the link (token in fragment) goes to the invitee by **email or a white-label link**. Adding people
   is **invite-only** — the owner never adds a user directly, and there is no directory lookup of arbitrary
   emails (account existence stays private).
3. The invitee accepts via the link, unwrapping the scope with the temp key and re-wrapping it to their own
   identity:
   - **New user** → [register-via-invitation](../../account/use-cases/uc.account.register-via-invitation.md)
     (creates the account in the same step).
   - **Existing user** → **confirm** the invite on their existing account (no new account).
4. Roles govern membership and settings — **content access is the wraps, not the role** (ADR 0005).

## Alternate / exception flows
- A1: **Remove a member** → membership becomes "Removed" (soft, rejoinable — ADR 0017); the grant is
  revoked + the **scope key is rotated** ([AD-24](../../../decisions/README.md)); content & attribution
  are preserved; rejoining re-grants access. If the scope already has a content key, rotation also
  re-wraps that stable content key to the new scope keypair so existing encrypted content remains
  readable by remaining members.
- A2: **Server Owner deactivation** of an account → **login blocked** (auth), keys untouched, reversible
  — a *global* action, distinct from tenant removal.
- A3: Change an existing member's role.
- E1: The owner doesn't hold the scope keys → issuing the invite is blocked (you can only hand on what you can read).

## Quality notes
- *Invite-only:* both new and existing users join through a **temp-wrap invite** (email / white-label link);
  the difference is only at accept time (register a new account vs. confirm on an existing one). There is no
  direct-add and no global directory lookup — the operator cannot be used to enumerate accounts.
- *Role ≠ access (ADR 0005):* roles govern membership/settings; the set of decryptable scopes governs
  content.
- *Removal = two-layer revocation ([AD-24](../../../decisions/README.md)):* instant token/access
  invalidation + eventual key rotation; already-downloaded content cannot be retracted. Scope-key
  rotation preserves the content key by re-wrapping it to the new scope keypair.

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Tenant Owner](../../../actors.md), [Invited User](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (provisioning, temp-wrap invites,
  content keys, revocation)
- Decisions: [AD-21](../../../decisions/README.md) (onboarding), [AD-03](../../../decisions/README.md)
  (content keys), [AD-24](../../../decisions/README.md) (revocation); ADR 0005, ADR 0017 (legacy)
- Related use-cases: `uc.account.register-via-invitation`, `uc.spaces.use-group`, `uc.account.erase-account`
