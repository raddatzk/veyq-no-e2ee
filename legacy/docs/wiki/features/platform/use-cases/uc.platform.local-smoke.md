# UC: Seed local smoke data

- **ID:** uc.platform.local-smoke
- **Primary actor:** Server Owner
- **Goal:** Create reproducible local web-smoke data through the normal E2EE client/API paths without
  writing directly to the database.
- **Preconditions:** The backend runs in a dev/test profile with dev tools enabled, and the frontend
  smoke command is invoked with `VEYQ_SMOKE_SEED=1`.
- **Postconditions (success):** A disposable local account exists, encrypted Memory content is seeded
  through the regular client/API flow, and a local seed file contains only the generated test
  credentials needed by the UI smoke.

## Main flow
1. The smoke command builds a server-owner registration payload with client-generated account key
   material.
2. The command calls the dev-only smoke account endpoint; production profiles do not enable this
   endpoint.
3. The command logs in with the generated password and Secret Key.
4. The command seeds Memory content, media, journal, tracks and playlists through the normal encrypted
   client/API paths.
5. The command writes a disposable local seed file for the browser smoke.

## Alternate / exception flows
- E1: The dev-only endpoint is unavailable or disabled -> the seed command fails without falling back
  to direct database writes.
- E2: Login or encrypted seeding fails -> no browser-smoke seed is reported as successful.

## Quality notes
- *Engineering-trust:* this flow exists to make visual and browser smoke checks reproducible while
  preserving the same E2EE boundaries as normal product flows.
- *Operator-blind:* the server persists account key material through the same account factory as setup
  and invitation registration; Memory content still reaches the backend only as encrypted data.

## Related
- Feature: [Platform](../_index.md) · Actors: [Server Owner](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md),
  [Platform Concepts](../../../crosscutting/platform-concepts.md)
- Related guide: `docs/ai/local-smoke.md`
