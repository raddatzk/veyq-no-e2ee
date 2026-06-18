# Design Notes & Open Questions

> Exploratory ideas and decisions-in-progress not yet stable enough for the main pages. Decided items
> graduate into [crosscutting](crosscutting/) / [decisions](decisions/README.md); open items are
> tracked here and in the decision log.

## Open decisions

1. **AD-18 params** — journal snapshot triggers (`X` interval, `Y` update threshold) and round-robin vs. presence-based leader.
2. **Second folder model — "Drive" (Later)** — a plain web-storage file tree (generic files and
   folders) alongside Memory curation, distinct from **curation folders**. Reuses scope keys + Agent
   Mode; becomes its own feature when scheduled.
3. **Trash & restore (Later)** — a user-facing soft-delete UX (trash → restore → purge) over the
   [AD-10](decisions/README.md) deletion model; a future `uc.memory.delete-restore`.
4. **Per-media caption (Later)** — a short one-line text per photo/video. Today the only text surfaces
   are the Memory **title** and the **Journal**.
5. **Later** — Key Transparency rollout; encrypted-relay CRDT details for shared journals; convergent-
   encryption-for-dedup tuning; full legal/DPA apparatus before SaaS launch.

6. **Key-directory trust hardening (Later)** — **manual key verification** (out-of-band fingerprint/QR,
   Signal-style) as a user action atop TOFU/KT; and **identity-key rotation** (re-wrap all grants
   old→new, sign with the old key, sync the new pair to all devices) for the compromised-key case. Both
   lean on Key Transparency.

## Delivery note — sequencing the E2EE rollout

The architecture target is **E2EE everywhere**. Delivery can still be incremental: the **personal
(unshared) scope** is the simplest, highest-value first slice (no sharing complexity); the
**shared-scope** key-management + Key Transparency investment can follow. The design assumes all-E2E
from day one so nothing is painted into a corner.

## Delivery note — legacy web UI port over the E2EE core

[AD-29](decisions/0029-legacy-web-ui-over-e2ee-core.md) makes the legacy web UI/UX the web product
baseline, while the Greenfield E2EE client/domain core remains the security and data baseline.

### Porting boundary

| Legacy asset | Direction |
|---|---|
| `legacy/frontend/src/components/app/AppShell.vue` | Port shell structure into the active web client. |
| `legacy/frontend/src/components/app/TenantSidebar.vue` | Adapt as the spaces/sidebar surface over decrypted `SpaceEntry` view models. |
| `legacy/frontend/src/components/app/WorkspaceTopbar.vue` | Port topbar ergonomics; bind search, settings and profile actions to the new session model. |
| `legacy/frontend/src/components/media/*` | Reuse gallery/lightbox behavior where it fits encrypted blob URLs and client-side decryption. |
| `legacy/frontend/src/styles/*.css` | Reuse visual system, spacing and responsive behavior; reconcile tokens with active `frontend/src/styles`. |
| `legacy/frontend/src/api/*` and generated clients | Do not port. Replace with the Greenfield OpenAPI/client and E2EE feature modules. |
| Legacy auth/tenant/share assumptions | Adapt only through new E2EE registration, scope grants, share-fragment keys and sealed metadata flows. |

### Adapter boundary

Legacy-style UI components should consume decrypted view models:

- `WebSpaceViewModel`: personal, tenant and group spaces with display names already opened where allowed.
- `MemoryLevelViewModel`: folders, Memories, chapters, covers and breadcrumb state.
- `MemoryDetailViewModel`: opened Memory metadata, media tiles, journal state, tracks, playlists and
  available actions.
- `ShareViewModel`: public-share state opened from the URL-fragment key and optional password material.

These adapters delegate crypto-sensitive work to the active Greenfield modules under
`frontend/src/features/**` and `frontend/src/shared/crypto/**`. UI code must not directly implement
content-key provisioning, scope-key unwrapping, slug-token generation, dedup-token generation,
share-key unwrapping or sealed blob parsing.

### Suggested phases

1. **Shell parity:** port AppShell, sidebar, topbar, public shell, theme tokens and base responsive
   behavior; keep the existing Greenfield Home view behind the shell if needed.
2. **Spaces and Memory home:** bind legacy navigation and workspace lists to Greenfield spaces,
   decrypted names and the encrypted Memory tree.
3. **Memory detail:** port the legacy detail ergonomics over Greenfield media, journal, map/track,
   playlist, cover, chapter and download flows.
4. **Sharing surfaces:** port share dialogs and public-share presentation over share keys in URL
   fragments, share passwords, notices/terms and upload-only roles.
5. **Auth/setup polish:** restyle setup, login, invite acceptance and recovery without weakening the
   Secret Key, recovery code, key bag or scope-grant flows.
6. **Legacy removal:** delete or quarantine unused copied components once their Greenfield-backed
   equivalents are live and covered by tests.

### Review checkpoints

- No Album or Explorer product language, routes, models, APIs or tables are reintroduced.
- Server-visible fields remain limited to operational metadata described in the Security Model.
- Every UI flow that creates, renames, moves, shares or uploads content goes through the Greenfield
  sealing/token/wrap helpers.
- Tests assert both the legacy UX expectation and the E2EE invariant where the flow crosses data,
  key, media, sharing, search or storage boundaries.

## Diagram candidates (PlantUML strengths Mermaid lacks)

Worth adding as the docs grow: **use-case diagrams** per feature (actors ↔ use-cases), **activity
diagrams with swimlanes** for complex flows (e.g. the Agent-Mode upload + fallback), **component
diagrams** for the bounded contexts. See [Patterns & Conventions](crosscutting/patterns-and-conventions.md)
and the feature indexes.

## Reference material being mined (legacy `docs/`)

`memory-product-context.md`, `architecture.md`, the legacy feature catalog `product/features/*.yaml`,
and the 20 ADRs under `docs/adr/`. These are **reference** and are retired section-by-section as these
pages and the active [Greenfield acceptance coverage](coverage/README.md) supersede them.
