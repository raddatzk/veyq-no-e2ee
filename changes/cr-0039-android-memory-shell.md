---
id: cr-0039
title: Android Memory shell + browse (native parity of cr-0030 / cr-0034)
status: done
satisfies: [uc.memory.browse]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Replace the Android app's `HomeScreen` landing stub with the **Memory shell** — the native parity of the web
shell (cr-0030) and the Apple shell (cr-0034): open the personal scope, list + decrypt the Memory tree on
device, **browse** folders + Memories, and **create / rename / delete** — bound to the existing `:client`
`VeyqClient` data layer. Built **fresh in parallel to Apple** (there is no legacy Compose UI), matching the
**same screens** as the Apple `HomeView` and the web shell. **Responsive** per the Android rule, mirroring
Apple's `NavigationSplitView` (spaces rail + content): the rail sits in a `PermanentNavigationDrawer` on
**Medium/Expanded** width (tablet, landscape) and collapses to a `ModalNavigationDrawer` on **Compact**
(phone) — drill-in itself is **not** list/detail but a single state-driven content view (see Phase 2).
A thin spaces rail (static "Personal" + a profile/sign-out bar) ships now; favorites, theme/language settings
and the real space switcher are **later slices** (their data layer — favorites, my-tenants — isn't ported to
Android yet).

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
- **Device-trust UI (regression caught here):** the old `HomeScreen` was the unlocked-landing that also
  carried the device-trust status + the **"Stop trusting this device"** untrust control. Replacing it with the
  shell would drop that — and it surfaced that **Apple's shell (cr-0034/0035) already silently dropped it**
  (its `testTrustDeviceLifecycle`/`testNoLoginAutoRestoreLifecycle` still reference the removed
  `Unlocked`/`Stop trusting this device` UI, so the four account use-cases' native e2e coverage was only
  *nominally* green). Decision (with the user): **don't replicate the regression** — keep a minimal
  trusted-status + untrust affordance in the shell's spaces rail (shown when `deviceTrusted`), so
  `uc.account.{set-device-security,trust-device,biometric-unlock,auto-unlock}` stay genuinely e2e-covered on
  Android, and the two instrumentation tests are updated to the shell (drawer-aware). The **Apple mirror** (add
  the same control + un-stale its two UITests) is its own work order, **cr-0042**.

## Phases

- [x] 1 browse logic — ported the client-side level + breadcrumb derivation as a pure `MemoryBrowse` helper in
  `:client` (parity of web `browse.ts` / apple `MemoryBrowse.swift`): `childrenOf(parentId, nodes)` → `Level`
  (folders, memories) ordered by `(orderIndex, name)`; `breadcrumb(nodeId, nodes, rootName)` → `[root,
  …ancestors, node]` (`Crumb`); `nodeById(id, nodes)`; plus the path-stack helpers `pathNavigatingTo(path,
  nodeId)` (truncate to a tapped crumb) and `pathAfterDeleting(path, nodeId)` (step out of a deleted subtree).
  Pure Kotlin/JVM (no Android SDK → headless-testable), `kind` from the generated `NodeKind`. · Done:
  `./gradlew :client:test --tests '*MemoryBrowseTest'` green (11).
- [x] 2 shell UI — replaced `:app` `HomeScreen.kt` (was the unlocked-landing stub) with the shell +
  `HomeModel` (a `mutableStateOf`-backed state holder over `VeyqClient` + `MemoryBrowse`; `AppModel` gained a
  read-only `activeClient` accessor, the twin of Apple's `app.client`). **Layout (parity of Apple's
  `NavigationSplitView`):** a thin spaces rail (static "Personal" entry + a profile/sign-out bar) in a
  `PermanentNavigationDrawer` on Medium/Expanded, collapsing to a `ModalNavigationDrawer` on Compact (closed by
  default — opens straight to the content, mirroring Apple's `preferredCompactColumn=.detail`).
  **Drill-in is state-driven, not list/detail:** `HomeModel.path` is the drill stack; the content renders
  `MemoryBrowse.childrenOf(currentParentId)` (breadcrumb + folder rows + Memory grid in a `LazyVerticalGrid`)
  and tapping a folder pushes onto `path` (re-render, parity of Apple's `model.level`/`model.crumbs`); a
  `BackHandler` closes the drawer first, else pops one path level. Create/rename/delete via `AlertDialog` +
  long-press `DropdownMenu` (Compose analog of Apple's `NodeMutationDialog`/`DeleteNodeDialog`); themed via the
  veyq Compose theme. Added `material-icons-extended` for the folder/photo/person glyphs (parity with Apple's
  SF Symbols). **Card styling matched to web/iOS:** Memory cards carry an accent-tinted 16:9 cover + glyph,
  folders an accent-tinted rounded icon tile, both with a soft elevation shadow + rounded corners; section
  headers show a muted count pill. · Done: `./gradlew :app:assembleDebug` green.
- [x] 3 coverage — `uc.memory.browse.yaml` adds the Android `MemoryBrowseTest` ref on the
  `client-side-breadcrumb` criterion (was web+apple only) and narrows the `gaps.android` line to "shell built +
  unit-covered; live UITest + favorites/settings/switcher pending". · Done: `python3 scripts/validate_coverage.py`
  green.
- [x] 3b trust-test repair — the shell replaced the unlocked-landing, so the two instrumentation tests that
  asserted on it (`RoundTripUiTest.{connectSignInUnlock,trustDeviceLifecycle}`,
  `NoLoginAutoRestoreUiTest.noLoginAutoRestore`) were updated to the shell: home marker `Unlocked` → the
  `Create` toolbar tag, and the sign-out / untrust controls reached via the rail drawer (`openRail()`, since on
  compact the rail is modal). Keeps the four account use-cases' Android e2e refs honest. · Done:
  `./gradlew :app:compileDebugAndroidTestKotlin` green.
- [x] 4 verify (compact) — the env-gated live UI round-trip (`:app:connectedDebugAndroidTest`,
  `RoundTripUiTest.connectSignInUnlock`) drove connect→sign-in→unlock→**shell** against the real dev backend
  (`http://10.0.2.2:8080`) on the **Pixel_10 (API-37)** phone: the shell opened the personal scope, **created a
  folder + a Memory** (sealed on-device), rendered them in the decrypted tree, **drilled into the folder and
  back** — proving browse + create + client-side navigation operator-blind end-to-end. Screenshots refreshed
  (`android-04-home` = the real shell, `android-05-folder` = drilled-in). · Done: `connectSignInUnlock`
  `TEST SUCCEEDED`. *(Prereq surfaced: the personal scope is tenant-scoped by Host — a tenant for the
  emulator's Host `10.0.2.2` had to exist first; see the retrospective + the screenshots README.)*
- [~] 4b verify (expanded/two-pane) — **deferred follow-up** (does not block close). The
  `PermanentNavigationDrawer` branch is **built** (`:app:assembleDebug` green) + code-reviewed but not run
  live: the `veyq_tablet` AVD would not finish a cold boot in this environment (hung twice), and forcing
  expanded width by resizing the phone (`wm size`) is defeated by the **auth screens**
  (ConnectScreen/SignInScreen, cr-0016 — out of this CR's scope) not laying out in an extreme landscape
  window. Pick up when a tablet AVD boots: rerun `connectSignInUnlock` on a Medium/Expanded device and confirm
  the rail + content show side-by-side.

## Definition of done

The Android app, after unlock, shows the personal space's Memory tree (folders + Memories) and supports
drill-in + create/rename/delete — built fresh in parallel to Apple, matching its screens, operator-blind
(names on device). Responsive: **compact (phone) live-verified**; the expanded/two-pane branch is built +
code-reviewed but its live run is blocked by emulator infra (phase 4b).
`./gradlew :client:test` + `:app:assembleDebug` green; the compact live round-trip green (browse + create +
drill, real decrypted tree); coverage updated; the parity ledger reads Memory-browse =
**web · apple · android ✅** (android = shell live on compact; expanded pending a bootable tablet AVD).

**Out of scope (later Android slices):** favorites, theme/language settings, the tenant/space switcher (need
the favorites + my-tenants data layer ported first); the Memory detail/gallery.

## Retrospective

- **Planning miss (assistant):** the CR was scoped as "mirror Apple's shell" and only listed what the *new*
  design adds — it never diffed what the *old* `HomeScreen` carried (the device-trust status + untrust
  control). Replacing the screen would have silently dropped a real capability. Root cause: "parity with
  Apple" was treated as "Apple is correct", but Apple's cr-0034/0035 had itself dropped that control and left
  its trust UITests stale. Fix: when a CR *replaces* a screen, diff the old screen's controls **and the tests
  that assert them**, not just the new layout.
- **Process gap (both):** the coverage guard only checks that a test *symbol resolves*, not that it still
  exercises live UI — so Apple's stale `testTrustDeviceLifecycle`/`testNoLoginAutoRestoreLifecycle` read green
  while asserting on removed markers. A check that flags e2e tests referencing absent testTags (or that runs
  the suite) would have caught the regression at its source. Worth an inbox item.
- **Execution (assistant):** phase 4 was planned as a phone+tablet live round-trip, but the environment wasn't
  checked up front. Three env snags ate time: (1) the dev backend was mid-failed-reload; (2) the `veyq_tablet`
  AVD would not cold-boot here (so expanded stayed unverified live); (3) forcing expanded by resizing the phone
  hit an unrelated auth-screen landscape-layout issue. For env-gated phases, confirm backend + bootable AVDs up
  front, and prefer a real tablet AVD over `wm size` hacks.
- **Process gap — the tenant seed (both):** the live shell first showed "Couldn't open your space" because the
  personal scope is **tenant-scoped by Host** and the fresh DB had no tenant for the emulator's Host
  (`10.0.2.2`). `seed-owner.mts` mints only a ServerOwner — there is no tenant seed, so the native content
  round-trip silently depended on a pre-existing tenant (the Apple verify had one). Fix: add a `seed-tenant`
  step (or document the requirement) so the screenshot/verify workflow is reproducible from a clean DB; this
  bit the workflow, not the code.
