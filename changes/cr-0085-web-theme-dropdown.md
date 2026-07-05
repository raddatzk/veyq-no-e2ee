---
id: cr-0085
title: Web theme top-bar control — a labelled System/Light/Dark dropdown instead of the mystery cycle-button
status: done
satisfies: [uc.settings.appearance-language]
adrs: ["0022"]
invariants: [operator-blind-e2ee]
---

## Goal

The top-bar theme control ([cr-0078](cr-0078-web-settings-hub.md) follow-up) was a single icon that **cycled**
system → light → dark on each tap — a "mystery meat" control: it neither signals it opens options nor lets you
pick one directly, and the `pi-desktop` (monitor) icon for *system* reads as "display", not "theme". Replace it
with a **labelled dropdown**: the trigger shows the current mode + a caret, the popup lists **System / Light /
Dark** with the active one checked. Device-local preference, unchanged (operator-blind).

## Context & constraints

- Durable truth: [uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md)
  ([ADR 0022](../docs/decisions/0022-settings-information-architecture.md)).
- **Reuse the existing pattern:** a PrimeVue `Menu` popup with items marked by `pi pi-check` for the active one —
  the same convention the `ProfileMenu` language quick-picker already uses. Reuses the existing
  `useThemeState`/`setThemePreference` + the `shell.themeSystem/Light/Dark` i18n keys.
- Web-only: no contract, no backend. Theme stays a top-bar quick control (not moved to settings); the settings
  hub's Appearance panel keeps its own full theme selector.

## Phases

- [x] web — replace the cycle button in `AppTopbar.vue` with a `Menu`-popup trigger (current icon + caret,
  `aria-haspopup="menu"`) whose items are System/Light/Dark (icon = the theme icon, or a check when active);
  a small `.topbar-theme-button` modifier widens the trigger for the caret. Test: the menu offers the three
  labelled options and applies the picked one. · Done when: `npm run check` green + live smoke.

## Definition of done

The top-bar theme control opens a labelled **System / Light / Dark** menu with the active mode checked, and
picking one flips the palette live. `npm run check` green (incl. the AppTopbar dropdown test); live smoke:
trigger → menu (✓ System / ☀ Hell / 🌙 Dunkel) → picking "Hell" switches the document to light + the trigger
icon becomes the sun.
