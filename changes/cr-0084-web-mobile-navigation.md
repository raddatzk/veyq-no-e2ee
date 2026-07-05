---
id: cr-0084
title: Web mobile navigation — wire the legacy bottom-sheet nav (hamburger) so Personal is reachable below the compact threshold
status: done
satisfies: [uc.spaces.switch-spaces]
adrs: []
invariants: [operator-blind-e2ee]
---

## Goal

Below the compact viewport threshold (≤720px) the inline space nav is CSS-hidden, but the **mobile bottom-sheet**
that the design source uses to replace it was never wired up when the shell CSS was vendored ([cr-0030](cr-0030-memory-shell-browse.md)):
the `.mobile-navigation-button` + `.mobile-nav-sheet` styles came over, but no button/Dialog used them — so on a
phone **Personal** (and favorites/groups) were unreachable. Port the legacy hamburger → bottom-sheet nav
verbatim (web/CLAUDE.md: *reuse the legacy UI, swap only the data layer*), wired to the current data.

## Context & constraints

- Durable truth: [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md).
- **Reuse, don't invent** (web/CLAUDE.md): the sheet markup + all its CSS classes (`.mobile-nav-sheet`,
  `.mobile-nav-section`, `.mobile-nav-item`, `.mobile-nav-dialog`, `.mobile-navigation-button`) already exist in
  `web/src/styles/layout.css` (vendored verbatim from `legacy/frontend-noe2ee`); only the template + the toggle
  state were missing. **No CSS, no new UI.**
- Web-only: no contract, no backend. Wired to the existing data — `listMyTenants()` (tenant switch), the
  `#~<username>` Personal link ([cr-0083](cr-0083-member-identity.md)), the token-less Shared link, and the
  favorites already computed in `AppSidebar`.
- Scope: tenant switch + Shared + Personal + favorites (all built). **Groups** join the sheet when the groups
  epic lands ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md)).

## Phases

- [x] web — add a `.mobile-navigation-button` (hamburger) that opens a PrimeVue `Dialog` bottom sheet
  (`.mobile-nav-sheet`) in `AppSidebar.vue`, with sections for the tenant switch, the Shared/Personal nav, and
  favorites; each item closes the sheet on navigate. i18n EN+DE (`shell.menu` / `shell.menuOpen`). Test: the
  hamburger opens the sheet and it lists the tenant switch + Shared + Personal. · Done when: `npm run check`
  green + live smoke.

## Definition of done

On a narrow viewport the hamburger opens a bottom sheet with the full navigation and **Personal is reachable
again**. `npm run check` green (incl. the AppSidebar mobile-nav test); live browser smoke at 682px: hamburger →
sheet (Bereiche + Shared/Personal) → tapping Personal navigates to `#~<username>` and closes the sheet.
