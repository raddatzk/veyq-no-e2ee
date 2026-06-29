---
id: cr-0036
title: Refine the appearance & language use-case + close its coverage
status: done
satisfies: [uc.settings.appearance-language]
adrs: []
invariants: []
---

## Goal

Theme + language switching shipped as UI (web cr-0031, Apple cr-0035) without a use-case, so it sat outside
the trust chain. It IS a conscious user goal with perceived value → promote it to a **use-case** and back it
with tests, so the catalog covers it.

## Context & constraints

- Durable truth: new [uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md)
  under a new lightweight [settings](../docs/features/settings/_index.md) feature (device-local preferences —
  client-only, server-blind).
- Apple `L10n`'s string table + resolution moved to **`VeyqKit.Localization`** (pure) so it's unit-testable
  without an app test target.

## Phases

- [x] 1 use-case — `docs/features/settings/_index.md` + `uc.settings.appearance-language.md` (Cockburn-lite +
  the bilingual section). · Done.
- [x] 2 tests — web `useThemeState.test.ts` (theme flips `.veyq-dark` + persists) + `setLocale.test.ts`
  (locale switches + persists); Apple `Localization` moved to VeyqKit + `LocalizationTests` (per-locale,
  en-fallback, unknown-key, de↔en key parity). · Done: web 5 + Apple 4 green.
- [x] 3 coverage — `uc.settings.appearance-language.yaml` (web unit + Apple unit/e2e refs; android gap);
  cr-0035 `satisfies` updated. · Done: `python3 scripts/validate_coverage.py` green.

## Definition of done

The appearance/language use-case is in the catalog with every criterion test-backed (or an explicit android
gap); `validate_coverage.py` green; web + Apple suites green. Web cr-0031 + Apple cr-0035 now satisfy it.
