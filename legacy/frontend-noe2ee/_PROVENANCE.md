# `legacy/frontend-noe2ee/` — pre-E2EE original web UI (DESIGN reference)

> Added by us as a read-only reference (this file is the only non-original file here). **Never edit this tree.**

## What this is

The web `frontend/` extracted verbatim from commit **`0075f77c`** (`git archive 0075f77c:frontend`) — the
**first** veyq architecture, **completely without E2EE**. It is the **clean product-UX / visual-design source**
for the Memory shell: the authenticated app shell (`components/app/AppShell.vue`, `TenantSidebar.vue`,
`WorkspaceTopbar.vue`), Memory browsing (`views/HomeView.vue`, `features/memories/`), and the media gallery
(`components/media/MediaGrid.vue` + `mediaGridLayout.ts`, `MediaCard.vue`, `MediaLightbox.vue`). 62 `.vue`,
each with a test.

## The three UI eras (so the confusion never recurs)

| | era | E2EE? | role now |
|---|---|---|---|
| **this dir** (`0075f77c:frontend`) | 1st architecture | **no** | **design / UX / visual reference** — port presentation FROM here |
| `legacy/frontend/` | 2nd attempt (E2EE, but a scope/key concept we've since rejected) | yes | reference **only** for *how E2EE forced a flow client-side* — NOT its concept; it's also over-grown (404 `.vue`) |
| `web/` (active) | current fresh-start (correct concept) | yes | the real client — **concept + data layer live here** (cr-0026/0027/0028, `K_SC`→`K_mem`→`K_blob`) |

## How to port from here

- **Take:** the visual design, component layout, the shell/sidebar/topbar structure, the gallery look
  (justified `mediaGridLayout.ts`), the lightbox.
- **Re-derive, do NOT copy:** anything this UI got from a **non-blind server** — e.g. server-computed
  `MemoryNavigation*` breadcrumbs/context, server-side search, server scope roots. Operator-blind means those
  move **client-side** (decrypt names with `K_SC`, build breadcrumbs from the local tree). `legacy/frontend`
  already shows *one* way to do that — glance at it for the pattern, not the concept.
- **Wire to:** the current `web/` data layer — `features/spaces/scopes.ts` (`openPersonalScope` → `K_SC`),
  `features/memory/tree.ts` (`listTree`/`createFolder`/`createMemory`/…), `features/memory/media.ts`
  (`addMedia`/`listMedia`/`openMedia`). Note cr-0028 has **no thumbnails/previews/cover** yet (deferred) — the
  gallery either shows full blobs or waits for a thumbnails enabler.
