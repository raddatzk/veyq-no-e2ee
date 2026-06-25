# ADR 0029: Legacy Web UI over the E2EE Core

## Status

Accepted

## Context

veyq has two useful assets that should not be conflated:

- the legacy web client contains the preferred product feel: shell, sidebar, workspace navigation,
  Memory detail ergonomics, public/share surfaces, responsive behavior and visual language;
- the Greenfield client/server contract contains the required privacy architecture: full E2EE, sealed
  content, blinded slug and dedup tokens, scope grants, share keys in URL fragments and a blind server.

Rebuilding the legacy backend/data model around E2EE would require rewriting the hardest invariants
inside an architecture that was not shaped around them. Continuing with the Greenfield UI while trying
to visually approximate the legacy experience would keep the security model intact, but would waste the
already-proven UX work and make the product feel worse than necessary.

This decision is constrained by:

- [AD-02](README.md): full E2EE everywhere; the server is always blind;
- [AD-03](README.md): envelope encryption and share tokens in URL fragments;
- [AD-16](README.md): the API is internal and OpenAPI-generated clients are implementation detail;
- [AD-26](README.md): encrypted names with blinded slug tokens;
- legacy [ADR 0004](../../adr/0004-remove-album-product-model.md): Album and Explorer must not return.

## Decision

Use the legacy web UI/UX as the product-facing baseline for the web client while keeping the
Greenfield E2EE client/domain core and backend contract as the architectural baseline.

The port direction is:

1. Preserve the new E2EE core modules and API semantics.
2. Port legacy shell, navigation, layout, reusable UI components and visual styling into the active
   `frontend/` client.
3. Bind those UI components through explicit decrypted view-model adapters instead of letting legacy
   components call old plaintext APIs directly.

The legacy UI may consume decrypted view models such as spaces, Memories, chapters, media tiles,
journal blocks and share summaries. It must not own key handling, sealed blob parsing, slug-token
generation, content-key lifecycle or share-key unwrapping.

## Consequences

Positive:

- veyq keeps the product experience that already works well for the maintainer and target workflow.
- The security model remains the hard boundary: old API clients, plaintext metadata assumptions and
  server-visible content flows are not revived.
- The active frontend can be rebuilt in slices: shell first, then Memory home, then Memory detail,
  then sharing/auth polish.
- The adapter boundary makes review easier because UI code receives already-opened domain models while
  crypto-sensitive behavior stays concentrated in the Greenfield feature modules.

Negative:

- The port is still a real migration, not a stylesheet swap. Legacy components assume old route,
  auth, tenant, media and share contracts that must be adapted or removed.
- Some legacy tests will need to be rewritten against E2EE flows instead of copied verbatim.
- PrimeVue-era component structure and the current Greenfield feature modules will temporarily coexist,
  so the migration needs explicit phase boundaries to avoid a mixed, hard-to-review frontend.

Accepted trade-off:

The legacy UI is treated as the UX source, not as the architectural source. Any conflict between
legacy ergonomics and the E2EE security model is resolved in favor of the E2EE model.
