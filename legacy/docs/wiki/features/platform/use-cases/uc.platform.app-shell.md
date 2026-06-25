# UC: Boot the web app shell

- **ID:** uc.platform.app-shell
- **Primary actor:** Memory Curator
- **Goal:** Open the web client and land on the right entry — first-run setup, login, or a clear error —
  by discovering the instance's state before any content loads.
- **Preconditions:** A browser loads the web app against a (possibly fresh, possibly unreachable)
  instance.
- **Postconditions (success):** The shell has resolved the instance state and routed to setup, login or
  an error view; no content is shown before the user is in the right place.

## Main flow
1. The web app **boots** and queries the instance's state.
2. A **fresh** instance (not yet set up) → routes to **first-run setup**.
3. An **already-set-up** instance → routes straight to **login**.
4. The chosen view renders; from there the normal account/space flows take over.

## Alternate / exception flows
- A1: **No instance URL** present (already-configured client) → boots straight to login.
- E1: The instance is **unreachable** → the shell shows a clear **error state** rather than a blank or
  broken screen.

## Quality notes
- *Robustness:* the boot path degrades gracefully — a fresh, configured, or unreachable instance each
  has a defined landing.
- *Engineering-trust:* this is the client **shell**, shared by every feature; it maps to no single
  product use-case (`capabilities: []`).

## Related
- Feature: [Platform](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md)
- Related use-cases: `uc.admin.setup-server`, `uc.account.login`
