---
id: cr-0031
title: Memory shell polish — ProfileMenu, fragment routing, favorites
status: done
satisfies: [en.memory.favorites, uc.spaces.switch-spaces]
adrs: ["0015", "0014"]
invariants: [operator-blind-e2ee]
---

> **Backfill record.** This CR was authored *after* the work shipped, to reconcile the durable state with what
> was built (the slices below were committed directly under the `cr-0031 phase A/B/C` label without a CR file
> first). Going forward, independent slices get their own CR up front. Web-only (ADR 0010); native parity is a
> later increment.

## Goal

Round out the web Memory shell (built on the verbatim design system + tenant switcher of cr-0030) with three
independent pieces of polish: a **ProfileMenu** (theme + language), **operator-blind readable URLs** (the tree
position in the URL `#fragment`), and **favorites** (pin a Memory, encrypted).

## Context & constraints

- Durable truth: [en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md),
  [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md),
  [ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md) (fragment routing),
  [ADR 0014 §7](../docs/decisions/0014-group-scopes-and-governance.md) (the group-favorites caveat).
- Contracts touched: `contracts/api/content.openapi.yaml` (the `favorites` tag — `GET/PUT
  /api/scopes/{scopeId}/favorites`); `contracts/api/scopes.openapi.yaml` (the tenant switcher
  `GET /api/scopes/my-tenants`, shipped under the cr-0030 label).
- Invariant at risk: **operator-blind** — slugs in a server-visible URL path, and a plaintext favorite flag,
  would both leak. Both are avoided (fragment-only slugs; an encrypted favorites blob).

## Phases

- [x] A **ProfileMenu (theme + language)** — `useThemeState` (`.veyq-dark` on `<html>`, persisted, system-follow)
  + i18n `setLocale`/`SUPPORTED_LOCALES` + `account.ts` (in-memory email) → the `.sidebar-profile` slot.
  Done when: `npm run check` green; theme + language switch + persist live. (commit e9480bda)
- [x] B **Fragment routing** ([ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md)) — harden
  `slugify` to URL-safe `[a-z0-9-]`; `features/memory/location.ts` (parse/resolve/build `#~me/…`); drop
  `/n/:id`+`/m/:id` for a single content host rendering browse-or-detail by `kind`; `pendingRoute` so a
  bookmarked deep link survives a cold start. Done when: `npm run check` green (102 tests); live in-session nav
  keeps `pathname /` with the slug in the fragment; cold-load deep link round-trips through login. (commit f36b3ec0)
- [x] C **Favorites** ([en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md)) — contract
  `favorites` tag; backend `features/content/favorites` + `V12__favorites.sql` (member-grant gated,
  `FavoritesSliceTest` 5/5); web `features/memory/favorites.ts` + `favoritesStore` + sidebar section + card
  heart pin + detail toggle (heart, accent⊕rosé). Done when: `npm run check` + `FavoritesSliceTest` green;
  pin/persist/operator-blind blob verified live. (commits 2194266d, bfe0cfde, 989827db, b03771f0)

## Definition of done

`npm run check` (web) + `./gradlew test --tests FavoritesSliceTest` (backend) green; the three pieces verified
live (theme/language switch, fragment navigation + cold-load deep link, favorite pin surviving reload). Durable
state reconciled: `en.memory.favorites`, `uc.spaces.switch-spaces`, ADR 0015 + ADR 0014 §7.
