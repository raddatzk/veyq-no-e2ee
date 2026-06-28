---
id: cr-0034
title: Apple Memory shell + browse (native parity of cr-0030)
status: planned
satisfies: [uc.memory.browse]
adrs: [0012]
invariants: [operator-blind-e2ee]
---

## Goal

Replace the Apple app's `HomeView` stub with the **Memory shell** — the native parity of the web shell
(cr-0030): open the personal scope, list + decrypt the Memory tree on device, **browse** folders + Memories,
and **create / rename / delete** — reusing the first iOS UI (`legacy/iOS/Veyq/Home/MemoryHomeView.swift` +
`HomeStore.swift`) component-for-component and binding it to the existing `VeyqClient` data layer. **Responsive
universal app** per the native UI rule: a `NavigationSplitView` (sidebar + content) on regular width (iPad
landscape, macOS), collapsing to a stack on compact (iPhone). Favorites + theme/language settings + the tenant
switcher are **later slices** (their data layer — favorites, my-tenants — isn't ported to Apple yet).

## Context & constraints

- Durable truth: [uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md) — this CR closes its
  **Apple shell-UI gap** (the data layer is already covered: `ContentTreeTests`/`ScopesTests`).
- Reuse: `legacy/iOS/Veyq/Home/MemoryHomeView.swift` (the shell layout — space menu, breadcrumb, folder +
  Memory grids, actions) + `HomeStore.swift` (state — **swap its legacy API calls for `VeyqClient`**) +
  `apple/App/VeyqStyle.swift` (already-ported cross-platform tokens). `legacy/` is read-only.
- Data layer (ready, no new work): `apple/Sources/VeyqKit/VeyqClient.swift` — `ensurePersonalScope` /
  `openPersonalScope` / `listTree(scopeId, scopeKey)` → `[TreeNode]` (names decrypted) / `createFolder` /
  `createMemory` / `renameNode` / `deleteNode`. The blind server computes no navigation → the level +
  breadcrumb are derived **client-side** in the store (mirrors web `browse.ts`).
- Invariant: **operator-blind** — names decrypted on device under `K_SC`; the server sees only ciphertext +
  shape. Native navigation is in-app (no URL fragment; ADR 0015 is web-only).
- Rule: **responsive** — design for the size class, verify on an iPhone **and** an iPad/Mac destination
  ([apple/CLAUDE.md](../apple/CLAUDE.md)).

## Phases

- [x] 1 store logic — the client-side level + breadcrumb derivation as pure `MemoryBrowse` helpers in VeyqKit
  (parity of web `browse.ts`) + a public `TreeNode` init for testability. · Done: `swift test
  --filter MemoryBrowseTests` green (5). The `HomeModel` that composes these over `VeyqClient` lands with the
  shell UI (phase 2).
- [ ] 2 shell UI — replace `apple/App/HomeView.swift` with the shell: `NavigationSplitView` (sidebar: the
  personal space; content: folder + Memory grid + breadcrumb + create/rename/delete), copying the legacy
  layout, styled via `VeyqStyle`, **responsive** (split on regular, stack on compact). · Done when:
  `xcodebuild … -destination platform=macOS` **and** `… generic/platform=iOS Simulator` build green.
- [ ] 3 coverage — extend `uc.memory.browse.yaml` Apple refs (the shell UI now covered: a UITest browse
  round-trip and/or the model unit test), narrow the Apple gap to what's still deferred (favorites, settings,
  switcher). · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

The Apple app, after unlock, shows the personal space's Memory tree (folders + Memories) and supports
drill-in + create/rename/delete — reusing the legacy iOS layout, operator-blind (names on device).
Responsive: verified on an iPhone **and** an iPad/macOS destination. `swift test` + both `xcodebuild`
destinations green; coverage updated. **Out of scope (later Apple slices):** favorites, theme/language
settings, the tenant/space switcher (need the favorites + my-tenants data layer ported first); the Memory
detail/gallery.
