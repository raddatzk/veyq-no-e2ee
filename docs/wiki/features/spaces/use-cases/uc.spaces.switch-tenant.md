# UC: Switch tenant

- **ID:** uc.spaces.switch-tenant
- **Primary actor:** Memory Curator
- **Goal:** Switch the active tenant context quickly.
- **Preconditions:** Active member of more than one tenant.
- **Postconditions (success):** The active tenant is switched; the UI scopes to it; the client uses that
  tenant's scope keys.

## Main flow
1. The user opens the **tenant switcher** (lists tenants they're an active member of).
2. They pick a tenant; the client switches the **active scope context** and uses that tenant's
   already-held scope keys.
3. Navigation, search and uploads now target the selected tenant.

## Alternate / exception flows
- A1: A **pending invitation** appears in the switcher → accepting it activates membership.
- E1: **Removed** from a tenant → it disappears from the switcher (no future access).

## Quality notes
- *Membership is server-visible:* which tenants you belong to is operational metadata; content stays
  encrypted.
- *Keys already held:* switching fetches no secrets — the client already holds the wrapped scope keys
  for the user's memberships.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (membership, scope keys)
- Decisions: [AD-02](../../../decisions/README.md) (E2EE)
- Related use-cases: `uc.spaces.use-group`, `uc.spaces.use-personal`
