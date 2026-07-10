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

## Follow-up 2 — overflow-clipping fix + readable group slug (2026-07-10)

Two issues the user hit after the overflow port, both in `AppSidebar.vue` (+ a small routing touch):

1. **Compact strip clipped the chevron at a narrow width.** The greedy packer had a `slice(0, 1)` fallback that
   **forced one group inline even when it didn't fit** — at a squeezed strip (measured **~109px** at 760px viewport)
   that one link (~140px) *and* the "＋N" chevron both overflowed the `overflow: hidden` strip, so the group was cut
   off **and** the chevron (the only way to reach the other groups) was clipped away entirely. Fixed by dropping the
   forced fallback: when not even the first link fits, **none** render inline and the chevron alone (48px, always
   fits) holds every group. Also removed the now-redundant trailing **"Neue Gruppe"** from the chevron menu — group
   creation already has the always-present "＋" in the section heading (restored in phase 1), so the menu is now
   purely the overflow groups and the chevron shows **only when something actually overflowed**.
2. **Group URL now carries a readable slug** — `#@familie-sued` instead of `#@<scopeId-UUID>`. `groupHash` emits
   `slugify(name)` when that slug is **unique among the caller's groups**, else the opaque scopeId. Groups (unlike
   content nodes, which the server keeps collision-free via a blinded `slugToken` + `UNIQUE(parentId)`) have **no
   server-enforced slug uniqueness**, so a same-name collision falls back to the unambiguous id rather than guessing.
   Resolution is a new **`openGroupByToken(token)`** in `scopes.ts`: one scope-list fetch, an exact-id fast path (no
   name decryption — keeps legacy/collision `#@<id>` links + `refreshActiveScope` working), otherwise the group whose
   decrypted name slugifies to the token, but only when **exactly one** does (an ambiguous slug resolves to nothing →
   Personal fallback). `enterScope`'s group branch calls it; `groupActive` now highlights by the **resolved**
   `activeSpace.scopeId` (format-independent — slug, id, or an old link all match the same open scope). Operator-blind
   is untouched: slugs are derived from **client-decrypted** names; the server still sees only ciphertext + the opaque
   scopeId.

3. **Tenant name no longer bold.** The active-tenant label (`.sidebar-tenant-text strong`) rendered at the
   `<strong>` UA weight (~700), sitting heavier than the regular-weight "Persönlich" nav link beside it. Set to
   `font-weight: 400` so it reads as a label level with its neighbour (the avatar carries the visual anchor);
   groups/favorites — which share the `<strong>` name-styling hook — keep their bold via the un-narrowed base rule.

**Verify:** `cd web && npm run check` (adds `openGroupByToken` unit tests — slug resolve, id-token resolve, ambiguous
→ null — and updates the `activeSpace` group-enter tests to the new resolver); a live smoke at a **narrow compact
width** with several groups (the chevron stays visible + reaches every group, nothing clipped), that entering a
group shows **`#@<slug>`** in the URL, and that the tenant name renders regular-weight. No contract/backend/crypto
change; no doc-graph change (still `uc.spaces.switch-spaces`).
