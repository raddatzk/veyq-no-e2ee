---
id: cr-0034
title: Apple Memory shell + browse (native parity of cr-0030)
status: done
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
- [x] 2 shell UI — replaced `apple/App/HomeView.swift` with the shell + `HomeModel` over `VeyqClient` +
  `MemoryBrowse`: a `NavigationSplitView` (spaces sidebar + profile/sign-out; content: breadcrumb + Memory grid
  + folder rows + create/rename/delete), copying the legacy layout, styled via `VeyqStyle`, **responsive**
  (split on regular, drawer/stack on compact). · Done: `xcodebuild` **macOS** and **iOS Simulator** both
  `BUILD SUCCEEDED`.
- [x] 3 coverage — `uc.memory.browse.yaml` adds the Apple `MemoryBrowseTests` ref on the client-side
  level/breadcrumb criterion; the Apple gap narrowed to "shell built + unit-covered; live UITest + favorites/
  settings/switcher pending". · Done: `python3 scripts/validate_coverage.py` green.

## Definition of done

The Apple app, after unlock, shows the personal space's Memory tree (folders + Memories) and supports
drill-in + create/rename/delete — reusing the legacy iOS layout, operator-blind (names on device).
Responsive: verified on an iPhone **and** an iPad/macOS destination. `swift test` + both `xcodebuild`
destinations green; coverage updated.

**Live-verified (2026-06-29):** the env-gated `RoundTripUITests.testConnectSignInUnlock` drove
connect→sign-in→unlock→shell against the real dev backend (`127.0.0.1:8080`) on **iPhone 17 Pro (iOS 26)**
and **iPad Pro 11" (iOS 26)** — both `TEST SUCCEEDED`. The shell renders the real decrypted tree (the
web-created "Italien 2027" Memory + "Reisen 2026" folder), proving the native client opens the same
operator-blind scope: **compact** opens to the browse with the spaces sidebar as a drawer
(`preferredCompactColumn = .detail`); **regular** shows the sidebar + content columns side by side. **Out of scope (later Apple slices):** favorites, theme/language
settings, the tenant/space switcher (need the favorites + my-tenants data layer ported first); the Memory
detail/gallery.
