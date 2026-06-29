---
id: cr-0039
title: Android Memory shell + browse (native parity of cr-0030 / cr-0034)
status: planned
satisfies: [uc.memory.browse]
adrs: [0012]
invariants: [operator-blind-e2ee]
---

## Goal

Replace the Android app's `HomeScreen` landing stub with the **Memory shell** — the native parity of the web
shell (cr-0030) and the Apple shell (cr-0034): open the personal scope, list + decrypt the Memory tree on
device, **browse** folders + Memories, and **create / rename / delete** — bound to the existing `:client`
`VeyqClient` data layer. Built **fresh in parallel to Apple** (there is no legacy Compose UI), matching the
**same screens** as the Apple `HomeView` and the web shell. **Responsive** per the Android rule: a
`ListDetailPaneScaffold` (list + detail) on **Medium/Expanded** width (tablet, landscape), collapsing to a
single pane on **Compact** (phone). Favorites + theme/language settings + the space switcher are **later
slices** (their data layer — favorites, my-tenants — isn't ported to Android yet).

This closes the last Memory-browse parity gap: web ✅ (cr-0030/0031) · apple ✅ (cr-0034) · **android ◻ → ✅**.

## Context & constraints

- Durable truth: [uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md) — this CR closes its
  **Android shell-UI gap** (the data layer is already covered: `ContentTreeTest`/`ScopesTest`).
- Reuse the UI/UX — **never invent new UI**. Match the Apple iOS shell
  ([`apple/App/HomeView.swift`](../apple/App/HomeView.swift) — space rail, breadcrumb, folder rows + Memory
  grid, create/rename/delete) and the web shell, realised in native **Material 3 themed to the veyq tokens**
  per [ui-android-styleguide.md](../docs/crosscutting/ui-android-styleguide.md). `legacy/android` is read-only
  structure reference only.
- Data layer (ready, **no new work**): `android/client/.../VeyqClient.kt` —
  `ensurePersonalScope` / `openPersonalScope` / `listTree(scopeId, scopeKey) → List<TreeNode>` (names
  decrypted) / `createFolder` / `createMemory` / `renameNode` / `deleteNode`. The blind server computes no
  navigation → the level + breadcrumb are derived **client-side** in the state holder (mirrors web `browse.ts`
  / apple `MemoryBrowse`).
- Invariant: **operator-blind** — names decrypted on device under `K_SC`; the server sees only ciphertext +
  shape. Native navigation is in-app (no URL fragment; ADR 0015 is web-only, so the Kotlin browse helper drops
  the `hash` the web version carries — exactly as `MemoryBrowse.swift` does).
- Rule: **responsive** — lay out by `WindowSizeClass`, verify on a **phone** *and* a **tablet/expanded**
  emulator ([android/CLAUDE.md](../android/CLAUDE.md)).

## Phases

- [ ] 1 browse logic — port the client-side level + breadcrumb derivation as a pure `MemoryBrowse` helper in
  `:client` (parity of web `browse.ts` / apple `MemoryBrowse.swift`): `childrenOf(parentId, nodes)` →
  (folders, memories) ordered by `(orderIndex, name)`; `breadcrumb(to, nodes, rootName)` → `[root, …ancestors,
  node]`; `nodeById(id, nodes)`. Pure Kotlin/JVM (no Android SDK → headless-testable), `kind` from the
  generated `NodeKind`. · Done when: `./gradlew :client:test --tests '*MemoryBrowseTest'` green.
- [ ] 2 shell UI — replace `:app` `HomeScreen.kt` with the shell + a `HomeModel`/state holder over
  `VeyqClient` + `MemoryBrowse`: a spaces pane (personal only for now — `ModalNavigationDrawer` on Compact /
  permanent pane on Expanded) + a profile/sign-out affordance; content = breadcrumb + folder rows + Memory grid
  + create/rename/delete dialogs (the Compose analog of Apple's `NodeMutationDialog`/`DeleteNodeDialog`),
  themed via the veyq Compose theme, **responsive** (`ListDetailPaneScaffold` on Medium/Expanded, single pane
  on Compact). · Done when: `./gradlew :app:assembleDebug` green.
- [ ] 3 coverage — `uc.memory.browse.yaml` adds the Android `MemoryBrowseTest` ref on the
  `client-side-breadcrumb` criterion (today web+apple only) and narrows the `gaps.android` line to "shell built
  + unit-covered; live UITest + favorites/settings/switcher pending". · Done when:
  `python3 scripts/validate_coverage.py` green.
- [ ] 4 verify — the env-gated live UI round-trip (`:app:connectedDebugAndroidTest`) drives
  connect→sign-in→unlock→shell against the real dev backend on a **phone** *and* a **tablet/expanded** emulator:
  the shell renders the real decrypted tree (the web-created Memory + folder), proving the native client opens
  the same operator-blind scope; Compact = single pane, Medium/Expanded = two-pane. · Done when: round-trip
  green + a screenshot captured per [`docs/design/screenshots/android/README.md`](../docs/design/screenshots/android/README.md).

## Definition of done

The Android app, after unlock, shows the personal space's Memory tree (folders + Memories) and supports
drill-in + create/rename/delete — built fresh in parallel to Apple, matching its screens, operator-blind
(names on device). Responsive: verified on a phone **and** a tablet/expanded emulator.
`./gradlew :client:test` + `:app:assembleDebug` green; the live round-trip green; coverage updated; the parity
ledger reads Memory-browse = **web · apple · android ✅**.

**Out of scope (later Android slices):** favorites, theme/language settings, the tenant/space switcher (need
the favorites + my-tenants data layer ported first); the Memory detail/gallery.
