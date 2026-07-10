---
id: cr-0124
title: Settings — /settings replaces to its default section URL on wide viewports
satisfies: []            # cross-cutting web IA (ADR 0022) — a redirect refinement to the settings hub, no new use-case
adrs: ["0022"]
invariants: [operator-blind-e2ee]
status: done
---

## Goal

Opening the gear from a Memory lands on `/settings`, which on a **wide** viewport already auto-shows a default
section's detail (the active scope's — the Tenant scope → members, a group → its page). The URL stayed the bare
`/settings` though, so it didn't reflect what's shown (or make it linkable). Now, on a wide viewport, `/settings`
(no section) **`router.replace`s** to `/settings/<defaultId>` (e.g. `/settings/tenant-members`) so the URL is
explicit. On **narrow** (≤720px) `/settings` stays the drill-down list — redirecting there would break the back
path — unchanged.

## Context & constraints

- **Web-only, one view.** `SettingsHubView.vue`: a `reflectDefaultSection()` that `router.replace`s to the resolved
  default section when there's no `:section` **and** `matchMedia('(min-width: 721px)')` matches (the same 720px
  breakpoint the split view already uses). Runs on mount + when `hasSection`/`defaultId` change. No
  contract/backend/crypto change; operator-blind untouched.
- **Contextual, not hardcoded.** The target is the existing `defaultId` computed (Tenant → `tenant-members`, a group
  → `group-<scopeId>`, else `appearance`) — so it matches whatever the wide view was already rendering.
- **Verify:** `cd web && npm run check` — `SettingsHubView.test.ts` updated: wide/no-param now asserts the redirect
  into the section (`is-detail`); a new narrow case asserts `/settings` stays the `is-index` list (matchMedia
  stubbed per case).

## Phases

- [x] 1 **web** — `SettingsHubView.vue`: wide-only `router.replace('/settings/' + defaultId)` when no section; test
  updated. · **Done:** `npm run check` green, 5 guards green.
- [x] 2 **apple / android** — **n/a** (web IA).
