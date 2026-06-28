---
id: cr-0030
title: Memory shell — personal-space tree browse (web)
status: done             # planned → in-progress → done (the file is kept as a record)
satisfies: [uc.memory.browse]
adrs: [0012, 0010]
invariants: [operator-blind]
---

## Goal

Give veyq its first **usable product surface**: the authenticated **app shell** + **personal-space Memory-tree
browse** on the web client. Today the data layer (auth → scopes → tree → media) is complete on all surfaces but
there is **no UI** that consumes it. This slice ports the **shell + folder/Memory navigation + create/rename/
delete** from the pre-E2EE design source (`legacy/frontend-noe2ee/`) onto the current E2EE data layer
(`features/spaces/scopes.ts`, `features/memory/tree.ts`). Names are decrypted **on device**; the breadcrumb and
tree shape are built **client-side** (the blind server computes no navigation).

**Web only** (ADR 0010 — web-first; native shells are later slices). **No new contracts, backend, or crypto.**

## Context & constraints

- **Design source = `legacy/frontend-noe2ee/`** (the pre-E2EE original, `0075f77c`; see its `_PROVENANCE.md`).
  Port presentation; **never** its non-blind data flows (it used a server-computed `MemoryNavigationContext` —
  re-derive client-side). `legacy/frontend/` is consulted only as an E2EE-pattern reference, never its concept.
- **Reuse UI/UX, swap only the data layer** ([docs/ai/README.md](../docs/ai/README.md)). Spine components:
  `components/app/AppShell.vue` (auth vs public layout), `TenantSidebar.vue` + `ProfileMenu.vue`,
  `WorkspaceTopbar.vue`, `views/HomeView.vue`, `features/memories/components/MemoryFolderOverview.vue` +
  `MemoryCard.vue`, the create/edit/delete dialogs.
- **Unlock-gated:** the shell needs `K_SC`, which needs the vaulted identity → reachable only **after unlock**.
- **No dev-only code.** Host→tenant resolution is normal: create a tenant whose `domain` = the host the backend
  sees (default Vite proxy ⇒ **`127.0.0.1`**, `changeOrigin` + port stripped). Documented in the web README, no
  seed/fallback in product code.
- **No media derivatives:** cards show an **icon + decrypted name**, no cover/thumbnail (deferred, ADR 0013).
- **Scope of this slice (mirror [uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md)):**
  shell + **personal-space** browse + create/rename/delete folders & Memories. **Out:** the Memory **detail
  view** (gallery/journal/chapters/map/playlists/participants/shares), media display, multi-space switching,
  favorites, search, settings/profile panels.

## Phases

- [x] **1 — active-space state + scope open.** App state holding the **active space** (`scopeId` + `K_SC`),
  populated after unlock: `ensurePersonalScope()` (idempotent auto-birth) → `openPersonalScope()` → hold the
  scope id + `K_SC`. Graceful **NoTenant** state (empty/blocked, with the dev hint). **Done:** unit test for
  the state transitions (mocked `scopesApi`); a locked/no-tenant path renders the blocked state.
- [x] **2 — app shell + routing.** Port `AppShell.vue` (authenticated layout = sidebar + topbar + content
  `RouterView`; public layout = bare `RouterView`), gated on **session + unlocked**. Add the `/` home route
  (+ a node route for folder drill-in, e.g. `/n/:nodeId`) redirecting to `/login` when not authed, to unlock
  when locked. Port a **simplified** `TenantSidebar` (Personal entry + `ProfileMenu`: logout / theme /
  language — defer space-switching, favorites, search) + `WorkspaceTopbar` (client-side breadcrumb + title).
  **Done:** `npm run check` green; the shell renders for an authed+unlocked session, the public layout for
  login.
- [x] **3 — browse view.** Port `MemoryFolderOverview.vue` + `MemoryCard.vue`: the current level's child
  **folders** + **Memories** as cards (icon + name) with *create* cards; wire to `listTree(scopeId, K_SC)` →
  filter children by `parentId`; build the **breadcrumb client-side** by walking `parentId` to the root.
  Folder click drills in (route); Memory click opens a **minimal placeholder** Memory view (title +
  breadcrumb + "gallery/journal coming" — the detail view is a later slice). **Done:** `npm run check` green;
  a component test round-trips a decrypted tree → rendered folders/Memories + breadcrumb (real v1 crypto +
  mocked `contentApi`).
- [x] **4 — create / rename / delete.** Port `CreateFolderDialog`, `CreateMemoryDialog`, `EditMemoryDialog`
  (rename), `ConfirmationDialog` (delete-subtree); wire to `tree.ts` `createFolder` / `createMemory` /
  `renameNode` / `deleteNode`; refresh the level after a mutation; surface `slug_taken` / `illegal_structure`
  inline. **Done:** `npm run check` green; dialog tests assert the data-layer calls + the post-mutation
  refresh.
- [x] **5 — verify (browser smoke). DONE.** Live stack (MariaDB + `quarkusDev` + `seed-owner.mts` + a dev
  tenant seeded with `domain = 127.0.0.1` + an OWNER membership + Vite): drove the full round-trip in the
  browser — **login → unlock (personal scope auto-births) → browse → create folder + create Memory → drill in
  → rename (persisted across reload) → delete (→ navigate to parent)**, all names decrypted on device, server
  blind. Dev tenant-domain documented in [web/CLAUDE.md](../web/CLAUDE.md). **Bug found + fixed in the smoke:**
  the create/rename/delete dialogs would not close — `defineModel('visible')` passed straight into PrimeVue
  `Dialog`'s `v-model:visible` under a parent `v-if` didn't propagate the close; rewrote the dialogs to be
  **mounted via `v-if` and closed by the parent on a `close` event** (no two-way `visible`), `npm run check`
  green (90 tests).

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Memory shell: personal-space browse + organize | Status |
|---|---|---|
| web | shell + tree browse + create/rename/delete | ✅ (P1–P5; live browser smoke green) |
| apple / android | the native Memory shell | ◻ **deferred (web-first)** |
| UI media | cover thumbnails in cards | ◻ **deferred (no derivatives yet)** |

## Definition of done

On the web, an unlocked member lands in their personal space and can browse their Memory tree (folders +
Memories, drill-in, client-side breadcrumb) and create / rename / delete folders & Memories — every name
decrypted on device, the server blind to names + navigation. Hermetic suites green (`npm run check`) + the
browser smoke round-trip. **The Memory detail view (gallery / journal / chapters) and media display are the
next slices.**
