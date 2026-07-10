---
id: cr-0120
title: Compact-band shell fixes — restore the tenant switcher + group create in the compact top bar (cr-0108 follow-up)
status: done
satisfies: [uc.spaces.switch-spaces]
adrs: ["0019"]
invariants: [operator-blind-e2ee]
---

## Goal

Fix three app-shell bugs the user hit at the **compact width (720–1020px)**, where the left rail flips to a
horizontal top bar (cr-0108). All three are web-only UI regressions from cr-0108's switcher merge, which left the
compact top bar half-styled (it referenced compact elements it never rendered).

1. **Uploads tray** — the empty state rendered **"Keine Uploads" twice** and the trigger button showed even with an
   empty queue. Now the trigger button appears **only when there is an upload** (active or a batch); the empty text
   renders **once**.
2. **Vertical divider** — a dangling `.compact-group-divider` sat right of the "Persönlich" nav link. Removed.
3. **Tenant switcher + group create missing** — the compact band hid the desktop `.sidebar-tenant-switcher` and the
   groups "＋", but the mobile hamburger only kicks in **≤720px**, so at 720–1020 **neither the shared-Tenant-home
   entry / tenant switch nor group creation was reachable**. Now the **tenant switcher stays** (avatar + name + a
   switch chevron when >1 tenant) and the **"＋ create group" stays**, laid out horizontally in the top bar.

## Context & constraints

- **Web-only, responsive-CSS + one template element.** No contract/backend/crypto change; operator-blind is
  untouched (the switcher already only knows membership; groups are `K_SC`-sealed as before). The fixes live in
  `web/src/styles/layout.css` (the `@media (max-width: 1020px)` block) + a two-line template edit in
  `AppSidebar.vue` (drop the divider `<span>`) + `UploadStatusMenu.vue` (the trigger `v-if` + de-dup).
- **Chosen approach (with the user): fix the compact top bar in place (option B),** not collapse it into the mobile
  hamburger (option A) — so the affordances stay **inline** at this width, matching cr-0108's intended compact
  design (the `.compact-menu-*` CSS was already written; the switcher/groups-action were simply hidden).
- **Group creation stays capability-gated** (`spaces.groups`, ADR 0032) — the "＋" only renders when the capability
  is active, unchanged.
- **Verify:** `cd web && npm run check`; a live smoke at **900px** (switcher + Personal + group-create "＋" all
  present, no divider; the "＋" opens the "Neue Gruppe" dialog) **and 1400px** (the vertical desktop sidebar
  unaffected — every changed rule is inside the ≤1020 query).

## Phases

- [x] 1 **web** — `UploadStatusMenu.vue`: the trigger button `v-if="hasUploads || activeFileCount"`; the header
  subtitle only shows the active summary (the empty "Keine Uploads" lives once in the empty row). `AppSidebar.vue`:
  drop the `.compact-group-divider` `<span>`. `layout.css` (≤1020): un-hide `.sidebar-tenant-switcher` + lay out
  `.sidebar-tenant-active` / `.sidebar-tenant-switch` horizontally; re-enable `.sidebar-groups-section
  .sidebar-section-action`; delete the now-dead `.compact-group-divider` rule. · **Done:** `npm run check` green
  (**556 tests** + build); live-verified at 900px + 1400px.
- [x] 2 **apple / android** — **n/a** (this is a web-shell responsive fix; native has its own size-class layout).

## Status note (implemented 2026-07-10)

Web-only, `npm run check` green. Live-verified after re-login: at **900px** the compact top bar shows the tenant
switcher ("Familie Raddatz"), the Personal link, and the group-create "＋" (which opens **"Neue Gruppe"**), with **no
vertical divider**; at **1400px** the desktop sidebar is unchanged. The uploads tray button is hidden when the queue
is empty (so the doubled "Keine Uploads" is unreachable). No trust-chain doc-graph change (a bug-fix against the
existing `uc.spaces.switch-spaces`); the three guards that touch docs stay green.

## Follow-up — measured group overflow (2026-07-10)

The compact groups strip previously **clipped** overflowing groups (`overflow: hidden`, no scroll). Ported the
design source's (`legacy/frontend-noe2ee/TenantSidebar.vue`) **measured "＋N" overflow** into `AppSidebar.vue` (the
CSS scaffolding — `.compact-group-overflow-button` + `.compact-group-measure` — was already in `layout.css` from
cr-0108, just never wired): a hidden off-screen clone measures each group's rendered width, a `ResizeObserver`
(RAF-debounced) re-runs on reflow, `visibleCompactGroups` greedily packs as many links as fit, and the rest collapse
into a PrimeVue `Menu` popup behind a chevron button (with a trailing **"Neue Gruppe"** action). Live-verified with 5
groups at **820px**: one group ("Familie Süd") fits inline + a "＋N" chevron whose menu lists the other four + "Neue
Gruppe". `npm run check` green (556 tests). i18n `shell.groupsMenuOpen` added.
