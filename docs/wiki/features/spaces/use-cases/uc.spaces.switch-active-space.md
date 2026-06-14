# UC: Switch the active space

- **ID:** uc.spaces.switch-active-space
- **Primary actor:** Memory Curator
- **Goal:** Switch the active space context quickly (between the personal space and any group space).
- **Preconditions:** Holds the key for more than one space (the personal scope and/or group memberships).
- **Postconditions (success):** The active space is switched; the UI scopes to it; the client uses that
  space's already-held scope keys.

## Main flow
1. The user opens the **space switcher** — one unified list of every space they can enter: their
   **personal** space plus every **group** they're an active member of and hold the scope key for.
2. They pick a space; the client switches the **active scope context** and uses that space's
   already-held scope keys.
3. Navigation, search and uploads now target the selected space.

## Alternate / exception flows
- A1: A **just-granted** group appears in the switcher once its grant is held (no user action needed).
- E1: **Removed** from a group → it disappears from the switcher (no future access).

## Quality notes
- *Roster is the wrap:* a space appears only if the caller actually holds its scope key (access = the
  wrap, ADR 0005) — membership without a grant is not an enterable space.
- *Switching fetches no secrets:* the client already holds the wrapped scope keys for its spaces;
  switching is pure client-side context, no server roundtrip for keys.
- *Membership is server-visible:* which groups you belong to is operational metadata; content stays
  encrypted.
- *Pending invitations are out of scope here:* member invites travel by link (not addressed to an
  account), so the server cannot list "your open invitations" — accepting an invite is its own flow.

## Related
- Feature: [Spaces](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (membership, scope keys),
  [Space Navigation](../../../crosscutting/space-navigation.md) (active scope context)
- Decisions: [AD-02](../../../decisions/README.md) (E2EE)
- Related use-cases: `uc.spaces.use-group`, `uc.spaces.use-personal`
