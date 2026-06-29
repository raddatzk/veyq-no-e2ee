---
id: cr-0037
title: Language gains a "system" option (symmetric with theme)
status: done
satisfies: [uc.settings.appearance-language]
adrs: []
invariants: []
---

## Goal

Theme already had *system / light / dark* (system = follow the OS live). Language only had en/de with a
**first-run** OS default that then froze. Make language symmetric: a **"system"** third option that is the
**default** and **follows the OS language live** — so both axes behave the same.

## Context & constraints

- Durable truth: [uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md)
  (A1/A2 updated — both axes default to *system* and follow the OS live).
- Web `vue-i18n` fires `languagechange`; Apple posts `NSLocale.currentLocaleDidChangeNotification` — both used
  to re-resolve while the preference is "system", without recreating any state.

## Phases

- [x] 1 web — i18n moves to a **preference** model (`system | en | de`, default system) + reactive
  `localePreference()` + `setLocalePreference()` + `effectiveLocale()`; a `languagechange` listener re-resolves
  while on system; ProfileMenu's language submenu adds **System**. · Done: `npm run check` green (122),
  `setLocale.test.ts` updated (+ a system case).
- [x] 2 apple — `VeyqKit.Localization.resolve(preference:systemLanguageCode:)` (pure); `L10n` holds the
  preference + an `effective` locale + observes the locale-change notification; `SettingsView` language picker
  adds **System**. · Done: `LocalizationTests` (resolve) green, macOS build green.

## Definition of done

Language offers *System / English / Deutsch*, defaults to System, and follows the OS language live (web +
Apple) — mirroring theme. `npm run check` + `swift test` (Localization) + `xcodebuild macOS` green;
`validate_coverage.py` green; `uc.settings.appearance-language` updated.
