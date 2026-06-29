---
id: cr-0035
title: Apple settings — theme + language (parity of the web ProfileMenu)
status: planned
satisfies: []
adrs: []
invariants: []
---

## Goal

Give the Apple app a **Settings** surface with **theme** (system / light / dark) and **language** (English /
Deutsch) switching + account + sign-out — the native parity of the web ProfileMenu (cr-0031 phase A), reusing
the first iOS settings UI (`legacy/iOS/Veyq/Settings/SettingsView.swift` + its `L10n.swift`). Reached from the
shell's profile bar. Pure client UI; no contract/crypto change.

## Context & constraints

- Reuse: `legacy/iOS/Veyq/L10n.swift` (the `T()` runtime i18n + the de/en string tables) +
  `Settings/SettingsView.swift` (theme + language pickers). `legacy/` is read-only.
- **Theme** is applied with SwiftUI `.preferredColorScheme` at the root, driven by `@AppStorage("veyq.theme")`;
  `VeyqStyle` colours are already light/dark adaptive, so forcing the scheme flips them.
- **Language** must switch **without recreating `AppModel`** (the `@StateObject` that holds the session). The
  legacy `.id(locale)` root-rebuild would also recreate the shell's `HomeModel` (a tree reload) — so use a
  reactive **`L10n: ObservableObject`** injected via the environment instead: `l10n.t(key)` re-resolves when
  `l10n.locale` publishes, no `@StateObject` is recreated.
- Default locale follows the system (de → Deutsch, else English), overridable + persisted.

## Phases

- [x] 1 i18n + theme infra — `apple/App/L10n.swift` (the `L10n` observable + the de/en tables, ported from
  legacy + the shell's keys); `RootView` injects `l10n` + applies `.preferredColorScheme` from
  `@AppStorage("veyq.theme")`. · Done: builds green (L10n lives in the app target → exercised by the live UI
  run rather than a separate unit-test target).
- [x] 2 settings UI + wiring — `apple/App/SettingsView.swift` (theme + language pickers + account + sign-out),
  reached from the shell profile bar (which now opens Settings); `HomeView` strings go through `l10n.t(...)`.
  · Done: `xcodebuild` macOS green; iOS Simulator builds + runs (the UI test).
- [x] 3 verify — live: launched with `-veyq.theme dark -veyq.locale de`, the shell renders **Deutsch + Dark**
  ("Persönlich" / "Erinnerungen" / "Ordner", dark palette) over the real backend, without losing the loaded
  tree. · Done: screenshot captured (iPhone 17 Pro / iOS 26).

## Definition of done

The Apple app has a Settings sheet (theme + language + account + sign-out); theme + language switch live and
persist across launches, the shell respects both, and switching language does **not** reload the tree.
`xcodebuild` macOS + iOS green; an `L10n` unit test green; live-verified on the iOS simulator.
**Out of scope (later):** localizing the auth screens (Connect/SignIn/Unlock stay English this slice);
favorites; the space switcher.
