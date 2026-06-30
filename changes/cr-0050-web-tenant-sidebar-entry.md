---
id: cr-0050
title: Web Server-Owner tenant administration — Server Settings page + ProfileMenu entry (web)
status: done
satisfies: [uc.admin.create-tenant]
adrs: ["0002", "0010"]
invariants: [operator-blind]
---

## Goal

Give the **Server Owner** a discoverable, in-app home for tenant administration. Today the `/tenants` screen
(Slice A, cr-0009: list + create + resend/reassign/revoke) is **effectively unreachable**: after login → unlock
the user is pushed to the Memory shell (`/`), the shell has no entry to it, and the only link (the `LoginView`
"unlocked" card) auto-pushes to `/` so it never stays. Typing `…/tenants` does a **full page load**, and the
session tokens live in memory only ([session.ts](../web/src/app/session.ts) — restore is a later CR), so the
guard bounces to `/login`. Net: a fresh operator who created a Server Owner is stranded on the "no space" state
with no way to create a tenant.

This CR adds, in the `legacy/frontend-noe2ee` style (markup + the **already-ported** `settings.css`/`ui.css`
classes, swapping only the data layer):

1. a **ProfileMenu → "Server settings"** entry (Server-Owner-only) → a new `/server/settings` route, and
2. a **Server Settings page** whose first rubric is **"Manage tenants"** — an **Add** button on top and a list
   of all tenants (`ManagementList` row style: `veyq-list-row` / `veyq-row-main` / `veyq-badge` / `management-
   actions`), each PENDING row carrying **edit** (reassign owner email / resend invite) and **delete** (revoke)
   on the right.

The standalone `/tenants` view is **retired**: its CRUD moves into the rubric and `/tenants` **redirects** to
`/server/settings` (so the entry is in-app navigation — the in-memory token survives, no reload). The sidebar
keeps **only** the membership switcher (no tenant-admin controls).

**Web only. No new contracts, backend, or crypto** — `features/admin/createTenant/tenants.ts` already exposes
`listTenants` / `createTenant` / `resendInvite` / `reassignInvite` / `revokeTenant`; the backend enforces
Server-Owner with `403 not_server_owner`.

## Context & constraints

- **Reuse legacy UI, swap the data layer** ([docs/ai/README.md](../docs/ai/README.md)). Design source =
  `legacy/frontend-noe2ee` `SettingsView.vue` (`veyq-page veyq-page-narrow settings-page` shell) +
  `ServerSettingsPanel.vue`'s **User-management** section (`settings-section-header` + `ManagementList` →
  `veyq-list-row` rows with a right-aligned `management-actions`). Legacy had **no** tenant rubric in server
  settings (tenant create lived in its sidebar); this is a **new rubric built in that same row style** —
  re-styling the existing `/tenants` CRUD, not inventing UI. All classes already exist in the ported
  `web/src/styles/settings.css` + `ui.css` (no new CSS).
- **The backend `403` is the role gate** (no client `serverRole` yet — `session.ts` carries only tokens). The
  ProfileMenu entry is shown only after a one-shot `listTenants()` **probe** succeeds; the Server Settings page
  itself maps `403 → "only the server owner"`. When member accounts land, the same 403 keeps both owner-only.
- **Two tenant sets stay distinct — an E2EE difference.** The sidebar switcher lists `listMyTenants()` = tenants
  the user holds a **key** to; the admin list is `listTenants()` = all server tenants. Creating a tenant never
  makes the Server Owner a member/key-holder (uc note *"Server Owner stays operator-blind"*), so these are
  disjoint. Tenant administration is therefore **not** in the per-tenant member sidebar — it lives in Server
  Settings.
- **Edit/Delete map to the existing API, status-gated.** Only **PENDING** tenants are mutable: **edit** =
  reassign the owner email (and resend the invite) — the invite lifecycle; **delete** = revoke. **ACTIVE**
  tenants have no rename/delete endpoint today, so their rows show the status badge with **no actions** (honest;
  an ACTIVE-tenant lifecycle is a separate future use-case per the uc's operator-boundary note).
- **Out of scope:** session/route restore on hard reload (the real fix for deep-linking `/server/settings`; its
  own CR — `boot.ts` notes the remembered-session machinery is deferred); the other legacy server-settings
  rubrics (server-info, users, license, support, update-log) — they have **no backend** in the fresh build yet,
  so the page ships with the tenant rubric only and a shell ready for them ("start light"); any auto-opening of
  the create dialog after first login (dropped).
- **Verify:** `cd web && npm run check` (typecheck + test + build); the crypto conformance test + the three
  trust-chain guards stay green; a **manual live browser smoke** against a fresh seeded instance — Server Owner
  signs in, ProfileMenu shows "Server settings", the page lists the seed tenant, **Add** creates a PENDING
  tenant, **edit**/**delete** act on it, all in-app (no re-login).

## Phases

- [x] 1 server-settings page + rubric — add `web/src/features/admin/serverRole.ts` (one-shot `listTenants`
  probe → reactive `canManage`); a `ServerSettingsView.vue` page (`veyq-page` shell) hosting a
  `TenantAdminPanel.vue` rubric (Add button + `ManagementList` rows; PENDING → edit[reassign/resend] + delete
  [revoke]; load maps 401→login, 403→notAuthorized); wire the `/server/settings` route + redirect `/tenants` →
  it + the `server-settings` session guard; add the ProfileMenu entry (gated on `serverRole.canManage`); remove
  the redundant `LoginView` *Manage tenants* button — and, as a follow-up, the whole vestigial post-unlock
  "Unlocked" confirmation card → a neutral redirect spinner (it only ever flashed before the auto-redirect, and
  its untrust / trust-failed affordances were already unreachable); retire `TenantsView.vue`. · Done when: `npm run check` green; `TenantAdminPanel.test.ts`
  covers list render + PENDING actions + 403-not-authorized + confirm-gated revoke + reload-on-created.
- [x] 2 coverage — repoint the web refs on
  [uc.admin.create-tenant.yaml](../docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml) from the
  retired `TenantsView.test.ts` to `TenantAdminPanel.test.ts` (create-pending-tenant + list-tenants), update
  `gaps.web`. · Done when: `validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.
- [ ] 3 verify (browser smoke) — live stack (MariaDB + `quarkusDev` + `npm run seed:dev` + Vite): a Server Owner
  signs in → unlock → ProfileMenu → "Server settings" → the tenant rubric lists the seed tenant → **Add** creates
  a PENDING tenant → **edit**/**delete** act on it → all in-app (no re-login). · Done when: confirmed with 1–2
  screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Server-Owner tenant administration entry (settings page + rubric) | Status |
|---|---|---|
| web | ProfileMenu → /server/settings → "Manage tenants" rubric | ◻ this CR |
| apple / android | the native server-settings / tenant-admin entry | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A signed-in Server Owner opens **ProfileMenu → Server settings**, lands on `/server/settings`, and in the
**Manage tenants** rubric creates a tenant (Add), edits a pending invite (reassign/resend), and deletes
(revokes) it — all in-app, operator-blind (no content key). The menu entry and page are owner-gated by the
backend 403; `/tenants` redirects in. `npm run check` + the conformance test + the three trust-chain guards
green; the live smoke confirmed with screenshots. Session restore on hard reload, the other settings rubrics,
and the native entries are deferred to their own CRs.

## Retrospective

_(to be written at close)_
