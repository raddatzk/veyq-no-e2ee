---
id: cr-0078
title: Web settings hub — one scope-contextual /settings page (NavigationSplitView), folding in tenant + server admin
status: done
satisfies: []            # cross-cutting web IA — realizes ADR 0022; re-homes existing settings, no new use-case
adrs: ["0022", "0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Realize the settings information architecture ([ADR 0022](../docs/decisions/0022-settings-information-architecture.md)):
consolidate every **configuration** surface into **one scope-contextual hub at `/settings`**, reached from a
gear in the top bar (beside Uploads), laid out as a responsive **`NavigationSplitView`** (sidebar↔detail on
wide, list→drill-in on narrow). Sections are filtered by the scopes in the caller's current path and by role;
per-content-object settings (folders, memories) stay **inline on the object** and are out of scope.

This is a **web-only** consolidation — no contract, no backend. The panels already exist
([`TenantAdminPanel`](../web/src/features/admin/createTenant/TenantAdminPanel.vue) from cr-0050,
[`TenantMembersPanel`](../web/src/features/spaces/TenantMembersPanel.vue) from cr-0077); this CR builds the hub
**shell** around them, moves appearance/language in, and cleans up the profile menu. Nothing is deployed, so
the old `/server/settings` + `/tenant/settings` routes are **replaced, not redirected**. **Groups are a
non-goal** (they arrive as another hub section in the groups work).

## Context & constraints

- **Route, not popup** ([ADR 0022](../docs/decisions/0022-settings-information-architecture.md)): the gear
  does `router.push('/settings')` → a real, deep-linkable page opened on the **current scope's** section.
- **`NavigationSplitView` pattern, responsive:** wide = persistent section sidebar + detail pane; narrow =
  section list, tap → `/settings/<section>` drill-in. One breakpoint, same routes. The native clients inherit
  the identical IA later (Apple `NavigationSplitView`, Android `ListDetailPaneScaffold` — [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)).
- **Sections = scope-in-path × role:** show a section only when the caller can see/do something there —
  **Persönlich** (always), **Tenant** (a tenant member; the member directory is any key-holder's, "invite" is
  owner-only), **Server** (Server Owner only). **Gruppe** is deferred. Role/scope come from data the client
  already holds (`listMyTenants().role`, the `serverRole` probe, the active space).
- **Reuse, don't rewrite:** the appearance + language controls already live in the profile menu (theme/language
  state from cr-0035/0037); the member + tenant panels already exist. This CR **re-homes** them — their
  behaviour and their existing tests stay intact (so `uc.settings.appearance-language` coverage is unaffected).
- **Operator-blind unchanged:** device-local preferences never reach the server; the tenant/server sections
  show only already-stored operational metadata. The hub is only *where* these render.
- **Verify:** `cd web && npm run check`; the three doc validators; a live browser smoke (Phase 3).

## Phases

- [x] 1 web — the **hub shell**: a `/settings` route + a `SettingsHub` view laying out a responsive
  **section sidebar ↔ detail** (`NavigationSplitView` behaviour; narrow collapses to a list that pushes
  `/settings/<section>`). A **scope/role-filtered section model** (Persönlich always; Tenant if a member;
  Server if Server Owner). Sections: **Persönlich** (theme + language, reusing the existing controls),
  **Tenant · Mitglieder** (`TenantMembersPanel`), **Server · Tenants** (`TenantAdminPanel`). Add the **gear**
  to the top bar (beside Uploads) → `/settings`; **remove** `/server/settings` + `/tenant/settings` and their
  profile-menu entries; keep the profile menu's quick theme/language toggles + sign-out + a single
  **"Einstellungen"** entry → `/settings`. i18n EN+DE for the shell labels. · Done when: `npm run check` green.
- [x] 2 coverage — component tests: the section nav is **filtered by role/scope** (a Server Owner sees Server;
  a plain member sees Tenant with no invite button; a non-member/​non-owner sees neither); the gear routes to
  `/settings`; the split view collapses to the drill-in list on a narrow viewport; a deep link
  `/settings/tenant/members` opens that section. Keep the moved appearance/language + member tests green
  (unchanged refs). · Done when: `npm run check` + `validate_coverage.py` + `validate_changes.py` +
  `validate_doc_links.py` green.
- [x] 3 verify (browser smoke) — live: the gear opens `/settings`; a tenant **owner** sees Persönlich + Tenant
  (with invite) + (if Server Owner) Server; a plain **member** sees Persönlich + Tenant (read-only, no invite);
  a section drills in and back on a narrow viewport. · Done when: confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | The settings hub (gear → scope-contextual `/settings`) | Status |
|---|---|---|
| web | `/settings` route + `NavigationSplitView` shell + Persönlich / Tenant / Server sections | ☑ this CR |
| apple / android | native hub (`NavigationSplitView` / `ListDetailPaneScaffold`) | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A gear in the top bar opens `/settings` — one scope-contextual hub laid out as a responsive
`NavigationSplitView`. Its sections are filtered by the caller's scope path + role (Persönlich, Tenant ·
Mitglieder, Server · Tenants; Gruppe deferred), each control role-gated. The former `/server/settings` +
`/tenant/settings` are gone, folded into hub sections; the profile menu keeps only "me" (quick toggles +
sign-out + a Settings entry). Folder/memory settings remain inline on their objects. `npm run check` +
the three doc validators green; the live smoke confirmed. Native hubs + the Groups section are later CRs.

## Retrospective

Built as specified — a web-only consolidation, no contract/backend touched.

- **The hub is a thin shell over existing panels.** [`SettingsHubView`](../web/src/views/SettingsHubView.vue)
  builds a scope/role-filtered section model and renders the active section via `<component :is>`; the sections
  are the untouched [`TenantAdminPanel`](../web/src/features/admin/createTenant/TenantAdminPanel.vue) +
  [`TenantMembersPanel`](../web/src/features/spaces/TenantMembersPanel.vue) plus a small new
  [`AppearancePanel`](../web/src/features/settings/AppearancePanel.vue) that reuses the existing theme/locale
  state (so the profile menu's quick toggles and the hub stay in sync). The two former views
  (`ServerSettingsView`, `TenantSettingsView`) were deleted, their routes replaced by `/settings/:section?`,
  and the profile menu's two settings links collapsed to one **Einstellungen** entry.
- **Responsive `NavigationSplitView` without a framework.** One flex layout + a `720px` media query: wide shows
  sidebar + detail; narrow toggles on an `is-index` / `is-detail` class (driven by the presence of the
  `:section` route param) to show the list or the drilled-in section with a back button. Section ids are
  `appearance` / `tenant-members` / `server` (the CR's `/settings/tenant/members` example was illustrative —
  the real deep link is `/settings/tenant-members`).
- **Live smoke (Phase 3) passed** with the owner account (Server Owner **and** tenant owner), so all three
  sections showed at once: the top-bar gear opened `/settings`, the split view rendered Persönlich · Geteilter
  Bereich · Server, the default section was the current scope (Mitglieder), and a deep link to
  `/settings/appearance` rendered the reused theme/language controls. Two screenshots captured. `npm run check`
  (392 tests) + the three validators green.

**Follow-up in the same session — theme is a top-bar toggle.** After the smoke, the quick **theme** control was
moved out of the profile menu into a one-tap **top-bar** button (beside search) that cycles system → light →
dark ([AppTopbar](../web/src/components/app/AppTopbar.vue)); **language** deliberately stays out of the top bar
(it grows more options) — it keeps its profile-menu quick-picker. Both remain fully in the hub's *Persönlich*
section (the canonical home). [ADR 0022](../docs/decisions/0022-settings-information-architecture.md) decision 5
updated to record this; a top-bar theme-cycle test was added.

**Honest scope note:** the component tests cover the section filtering, the default/deep-link section, and the
`is-index`/`is-detail` state (the JS half of the collapse). The *visual* narrow-viewport collapse is pure CSS
(a media query) and isn't asserted in jsdom; the plain-member read-only case is covered by the hub filtering
test + `TenantMembersPanel`'s own owner-gating test rather than re-run live. The optional desktop *overlay*
look over these routes, and the Groups section, are later work (ADR 0022 leaves both open).
