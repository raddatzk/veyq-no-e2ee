---
id: cr-0043
title: Android settings — theme + language (parity of cr-0035/0037)
status: done
satisfies: [uc.settings.appearance-language]
adrs: []
invariants: []
---

## Goal

Give the Android client the **theme + language settings** web and Apple already have
([uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md)):
a Settings surface to pick a **theme** (system / light / dark, flips the whole palette live + persisted) and a
**language** (system / en / de, re-renders every visible string live with **no content/navigation reload** +
persisted). Today Android only follows the system dark-mode (`VeyqTheme(darkTheme = isSystemInDarkTheme())`)
and ships **hard-coded English** Compose strings with no i18n layer at all — so it's the one started surface
out of parity. Pure client UI + a new client-local i18n layer; no contract/crypto/server change, operator-blind
untouched (everything is device-local preference).

## Context & constraints

- **i18n approach decided (refined 2026-06-29): mirror Apple's, not the OS-native one.** A Kotlin
  `Localization` (en/de table + `string(locale,key)` + `resolve(preference, systemLang)` with English
  fallback, pure + unit-tested) plus a reactive `L10n` exposed via a Compose `CompositionLocal`. Switching
  language re-renders strings **without** an Activity recreation — so the open Memory path/tree survives the
  switch (Apple's `Localization`/`L10n` from cr-0035 is the spec, "switching language never reloads the
  tree"). The OS-native path (`values-de/` + `AppCompatDelegate.setApplicationLocales`) was rejected: it
  triggers Activity recreation (screen flicker + transient state like `HomeModel`'s path must be re-saved) and
  diverges from the Apple pattern.
- **Persistence = `SharedPreferences`** (the established non-secret convention, as `LaunchPrefs` / `"veyq.launch"`):
  a small settings prefs file holds the theme + language preference. No DataStore.
- **Theme**: a preference (system/light/dark) feeds `VeyqTheme(darkTheme = …)` instead of the bare
  `isSystemInDarkTheme()`; "system" keeps following the OS live.
- **Where the resolution lives**: the pure `Localization` table/resolver as plain Kotlin testable by a JVM
  unit test (`:app` `testDebugUnitTest`, the analog of Apple's `VeyqKitTests/LocalizationTests`); `L10n` +
  the `CompositionLocal` in `:app`.
- **Settings UI placement** (deliberately idiomatic, not identical to Apple): a Settings screen/sheet reached
  from a gear entry in the `SpacesRail` (the Compose analog of Apple's `profileBar` → `SettingsView`). The
  existing untrust + sign-out stay in the rail (live-verified in cr-0039) — Settings adds theme + language
  (+ account email / instance for parity of content). ADR 0010 allows per-platform-idiomatic UI; the contract
  parity is the use-case, not pixel-identical chrome.
- **Strings in scope**: every visible Compose literal — `HomeScreen`/`SpacesRail`, `SignInScreen`,
  `UnlockScreen`, `KitScanner`, `RootScreen` — routed through `L10n`, with en + de entries.
- Durable truth: [uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md);
  coverage gap to close: [its YAML](../docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml)
  (`gaps.android`). Parity reference: Apple [cr-0035](cr-0035-apple-settings-theme-language.md) +
  [cr-0037](cr-0037-language-system-option.md).

## Phases

- [x] 1 i18n layer — pure `Localization` (en/de table + resolve + English/identity fallback) + reactive
  `L10n` (`CompositionLocal`, system/en/de preference persisted in `SharedPreferences`); route every visible
  Compose string through it. · Done when: `:app` JVM unit test for `Localization` (resolution + fallback +
  every key has both translations) green; `./gradlew :app:assembleDebug` green.
- [x] 2 settings UI — a Settings screen/sheet (theme system/light/dark + language system/en/de + account /
  instance), reached via a gear entry in the `SpacesRail`; theme preference drives `VeyqTheme`. · Done when:
  `./gradlew :app:assembleDebug` green **and** the env-gated live emulator round-trip shows the theme flip +
  language switch live (navigation-stable, no reload) on a **phone** and an **expanded** window.
- [x] 3 coverage — add the Android refs to `uc.settings.appearance-language.yaml` (unit for
  resolution-fallback + theme/language; e2e for the switch) and drop `gaps.android`. · Done when:
  `python3 scripts/validate_coverage.py` green.

## Definition of done

Android has a Settings surface to pick theme + language; theme flips the palette live + persists, language
re-renders strings live without reloading the tree + persists, an unknown locale falls back to English and
every key has both translations. `:app:assembleDebug` green; the i18n unit test green; the live emulator
round-trip green on phone + expanded; `validate_coverage.py` green with the Android gap closed. Settings parity
= web · apple · android.

## Retrospective

**What shipped.** A pure `com.veyq.app.i18n.Localization` (en/de table + `string`/`resolve`, JVM-unit-tested
in `:app testDebugUnitTest`) and a reactive `L10n` provided via `LocalL10n` at the composition root. Every
visible string across the auth + shell + settings surfaces routes through it; a `ModalBottomSheet` Settings
sheet (theme + language segmented controls + account/instance) opens from a gear in the `SpacesRail` profile
bar; the theme preference drives `VeyqTheme` at `MainActivity`. Live-verified on phone **and** expanded.

**Planning — what was off.**
- The CR scoped strings to a named screen list (`HomeScreen`/`SpacesRail`, `SignInScreen`, `UnlockScreen`,
  `KitScanner`, `RootScreen`) but the stated principle was "every visible Compose literal". Those conflict:
  `ConnectScreen`, `DeviceSecurityChooser`, `KitImportBar` and the shared component call-sites also carry
  visible literals. I routed **all** of them (a half-translated UI would have been a worse outcome) — but the
  CR should have either listed them or said "all surfaces". Model-returned error strings in `AppModel`
  (login/connect/unlock failures) are **deliberately left English** — routing them needs the ViewModel to hold
  a locale, which is a separate concern; flag for a follow-up if German error copy is wanted.
- The plan asserted the live round-trip as a clean done-gate, but the **infra to run it did not exist**: no
  tenant for the emulator Host, and (as it turned out) the Host `10.0.2.2` is a bare IP that the tenant-domain
  validator **rejects** — so the documented "seed a tenant whose domain = Host" recipe from cr-0039 cannot
  actually be followed as written. The plan inherited that recipe without testing it.

**Execution — what bit (both directions).**
- *Mine:* the `L10n` mutators `setTheme`/`setPreference` clashed with the generated property setters
  (`setTheme`) — JVM signature collision; renamed to `applyTheme`/`applyLanguage`. `kotlin.test` didn't bind
  on the Android unit-test classpath; switched to JUnit4 directly.
- *Environment / test harness:* three real obstacles surfaced, none in the feature code — (a) `ModalNavigationDrawer`
  composes its drawer content **off-screen while closed**, so the existing `openRail()` test helper (gating on
  node *presence*) silently no-opped and the gear/untrust controls were never actually on screen;
  `performClick` tolerates that but `assertIsDisplayed` doesn't, which is why a *pre-existing* test
  (`trustDeviceLifecycle`) flaked once I exercised the rail — fixed the shared helper to gate on **displayed**.
  (b) `ModalBottomSheet` renders in a **separate window**, so `onRoot().captureToImage()` is ambiguous — added
  a `UiAutomation` whole-screen capture for the settings shots. (c) the running dev backend logged only to its
  console, so `seed-tenant.mts` (which reads the invite link from a log file) couldn't source the bootstrap
  secret — had to restart the backend with stdout redirected, then seed a valid-hostname tenant and rewrite
  its `domain` to `10.0.2.2` in the DB.

**Next time.**
- When a CR's done-gate is a live round-trip, **smoke the seeding recipe first** (or at least confirm the
  fixture exists) before writing the gate — the `10.0.2.2`-is-an-IP rejection would have been caught in
  minutes and belongs in the plan, not discovered at verification.
- The Compose UI-test helpers (`openRail`, screen capture) now encode the modal-drawer/separate-window gotchas;
  the **fixes are reusable** — future shell tests should gate on displayed-state and use the device-screenshot
  helper for anything behind a sheet/popup.
- Consider documenting (or scripting) the "seed valid hostname → rewrite domain to the emulator IP" step in
  the Android screenshots README so the next person doesn't rediscover it (done here).
