---
id: cr-0108
title: Web — merge the tenant switcher into the active-tenant row (chevron overview)
status: done
satisfies: [uc.spaces.switch-spaces]
adrs: ["0002", "0019"]
invariants: [operator-blind-e2ee]
---

## Goal

Collapse the sidebar's two near-duplicate tenant affordances into one. Today the shell shows the **tenant
switcher** (a flat inline list of every tenant, under the *Bereiche/Spaces* header) and — in a separate `<nav>`
right below — a **Tenant-space link** (`pi pi-users` + the tenant name) that enters the shared tenant home. Both
read "Familie Raddatz", so the rail says the tenant's name twice. Merge them into a **single active-tenant row**:
its body enters the shared Tenant space (what the old nav link did), and a **chevron** on its right opens an
**overview of all the member's tenants** to switch between them (what the old inline list did). The row's icon
becomes the **tenant avatar** (the tenant's initial), replacing the two-person `pi pi-users` glyph. **Web only —
no contracts, backend, or crypto**: the same `listMyTenants()` membership data drives it, switching stays a full
navigation to the tenant's domain ([ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md)), and the
shared home stays the token-less fragment ([ADR 0019](../docs/decisions/0019-token-less-tenant-home.md)).

## Context & constraints

- **Refined state this builds on:**
  [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md) — main flow steps 1–2
  now describe the merged active-tenant row + chevron overview, and A1 (single tenant) hides the chevron. The
  behaviour is unchanged; this is a **presentation refinement** of an existing capability, not a new concept.
- **Presentation-only — no wire change, no new bytes.** The overview lists exactly what the inline switcher
  listed: the `MyTenant[]` from `GET /api/scopes/my-tenants` (name + domain + role — membership metadata already
  sanctioned by the operator-blind invariant, per the use-case's quality notes). Nothing new is fetched, sealed,
  or sent; **operator-blind is untouched**.
- **Body vs. chevron split.** The row **body** inherits the old Tenant-space nav link: it navigates to the
  token-less tenant home (`{ name: 'home', hash: '' }`) and carries the active highlight when the tenant scope
  is active (`tenantActive`, driven off the parsed leading token — both space entries share the `/` path, so we
  keep the existing `RouterLink custom` + manual `router-link-active`, as cr-0075 established). The **chevron** is
  a separate `@click.stop` toggle that opens the overview; clicking it must **not** navigate the row.
- **Reuse the existing primitive + rows, don't invent UI** ([docs/ai/README.md](../docs/ai/README.md),
  [web styleguide](../docs/crosscutting/ui-web-styleguide.md)). The overview is a **PrimeVue `Popover`**
  (`primevue/popover`, already a dependency at 4.5.5) containing the existing `.sidebar-tenant-link` rows
  (avatar + name + domain) — so each tenant in the overview looks identical to today's switcher entries. The
  chevron follows the established `ProfileMenu`/`AppTopbar` pattern (`pi pi-chevron-down` + a toggled overlay).
  The switcher row's grid already reserves a trailing `auto` column
  ([layout.css](../web/src/styles/layout.css) `2.35rem minmax(0,1fr) auto`), so the chevron drops in without a
  layout change.
- **`TenantAvatar` as the one swap point.** Extract the repeated
  `<span class="sidebar-tenant-avatar"><span>{{ initial }}</span></span>` markup into a small `TenantAvatar`
  component (initial today; `.sidebar-tenant-avatar img` **already exists** in `layout.css`, so a future
  tenant-chosen **icon** becomes an `<img>` fallback-to-initial swap in exactly one place). Reused by the
  active-tenant row, the overview rows, and the mobile sheet. **The icon field itself is out of scope** — it is
  not on the `MyTenant` contract and would be its own contract + CR when it lands (tracked in `docs/inbox.md`).
- **Single-tenant is the common self-hosted case.** When `tenants.length <= 1` the chevron/overview is hidden
  (nothing to switch to); the row is then simply the Tenant-space entry.
- **Mobile sheet untouched.** The compact bottom-sheet ([AppSidebar.vue](../web/src/components/app/AppSidebar.vue)
  `mobile-nav-sheet`) already separates a full tenant list from the Shared/Personal section and has room for it;
  a popover-in-a-sheet would be fumbly. It keeps its flat list (only its avatar markup adopts `TenantAvatar`).
- **Non-goals:** the tenant **icon** field/upload (own contract + CR), any change to how a scope is opened or to
  the fragment grammar, groups/sharing, and the native heads (web-first per
  [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md); their own CRs).
- **Verify:** `cd web && npm run check` (typecheck + vitest + build); the crypto conformance test + the three
  trust-chain guards stay green; a **live browser smoke** (Phase 3).

## Phases

- [x] 1 web — **`TenantAvatar` + merge.** (a) Extract `TenantAvatar.vue` (props: `name`; renders the uppercased
  initial via the existing `.sidebar-tenant-avatar` markup, `<img>`-ready for a later icon) and use it in the
  switcher row + mobile sheet. (b) In [AppSidebar.vue](../web/src/components/app/AppSidebar.vue) replace the
  inline `v-for` tenant list **and** the separate Tenant-space `<nav>` link with a **single active-tenant row**:
  `TenantAvatar` + name + domain (keep the `tenantUnavailable` → *awaiting access* subtitle), body =
  `RouterLink custom` to `{ name: 'home', hash: '' }` with `router-link-active` bound to `tenantActive`, plus a
  trailing **chevron button** (`v-if="tenants.length > 1"`, `aria-haspopup="menu"`, `@click.stop`) that toggles a
  `Popover` listing all tenants as `.sidebar-tenant-link` rows (active one marked/inert, others
  `@click="switchTenant(tenant)"`). Personal stays the next `<nav>` link. i18n EN+DE for the new labels
  (chevron `aria-label` + overview heading) in [i18n/index.ts](../web/src/i18n/index.ts). · Done when:
  `npm run check` green; [AppSidebar.test.ts](../web/src/components/app/AppSidebar.test.ts) covers — the merged
  row navigates to `/` and highlights on the token-less home; the chevron is **absent** with one tenant and
  **present** with two; the popover lists all tenants with the active one marked; picking another calls
  `switchTenant` (full-nav to its domain); the mobile sheet still lists tenants + Shared/Personal.
- [x] 2 coverage — add a **tenant-switcher-overview** criterion to
  [uc.spaces.switch-spaces.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.switch-spaces.yaml)
  (the merged active-tenant row + chevron overview), mapped to the Phase-1 tests, and re-check the
  `token-less-tenant-default` / `personal-tenant-switch-resets` / `mobile-nav-sheet` refs still resolve after the
  markup change; apple/android stay the existing web-first gaps. · Done when: `python3
  scripts/validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.
- [x] 3 verify (browser smoke) — live stack (the `bootstrap:user` principal on `veyq.localhost`, per
  [web/CLAUDE.md](../web/CLAUDE.md)): the merged row shows *Familie Raddatz* with the `[F]` avatar, its body
  enters the shared home (highlighted), and Personal switches below it. **Gap (single-tenant instance):** the
  bootstrap principal belongs to one tenant, so the **chevron is hidden** and the overview can't be shown live —
  the >1-tenant overview + switch is covered by the Phase-1 unit tests (a live multi-tenant check would need a
  second tenant seeded for the same principal). · Done when: confirmed with 1 screenshot. **Confirmed:** the
  `BEREICHE` section renders one `[F] Familie Raddatz / veyq.localhost` row (gradient avatar, `href="/"`,
  `router-link-active`), **no** `pi-users` people icon and **no** chevron (single tenant), Personal below.
- [x] 4 web — **mobile-sheet parity.** The same merge in the compact bottom-sheet (`mobile-nav-sheet`): the
  **active tenant row enters the shared Tenant home** (`{ name: 'home', hash: '' }` + close, highlighted on
  `tenantActive`), the standalone `pi pi-users` **"Gemeinsam" item is removed** (folded into that row), and
  **Personal moves up** into the same `BEREICHE` group; the second (`tenantLabel`) section is dissolved. No
  popover here — the full sheet lists other tenants inline as switches. `tenantLabel` computed dropped (now
  unused). · Done when: `npm run check` green; `AppSidebar.test.ts` covers the sheet's active-tenant row entering
  `/` with no standalone "Shared" item; live smoke in a narrow viewport.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Merge the switcher into the active-tenant row + chevron overview | Status |
|---|---|---|
| web | `TenantAvatar` + merged row + `Popover` overview + mobile-sheet parity in `AppSidebar.vue` | ✅ this CR |
| apple / android | native tenant switcher not built yet | ◻ **deferred (web-first; own CRs)** |

## Definition of done

The web sidebar shows the active tenant as **one row** (tenant avatar + name + domain) whose body enters the
shared Tenant home with the active highlight; a **chevron** — present only when the member belongs to more than
one tenant — opens a `Popover` overview of all their tenants, and picking another navigates to that tenant's
domain. The old separate `pi pi-users` Tenant-space link and the flat inline switcher list are gone; the avatar
comes from a reusable `TenantAvatar` (icon-ready). The **compact bottom-sheet mirrors the merge** (active-tenant
row enters the shared home, the standalone "Gemeinsam" item removed, Personal folded up). Personal itself is
unchanged. Operator-blind untouched — same `listMyTenants()` data, no new bytes; contracts + backend + crypto not
touched. `npm run check`
+ the conformance test + the three trust-chain guards green; `docs/coverage/` updated for
`uc.spaces.switch-spaces` with `python3 scripts/validate_coverage.py` green; the live smoke confirmed with a
screenshot.

## Retrospective

Delivered as scoped — web-only, no contracts/backend/crypto. The merge reused the existing `.sidebar-tenant-link`
grid (its trailing `auto` column already left room) and extended one CSS selector
(`.sidebar-tenant-link-active .sidebar-tenant-avatar, .sidebar-tenant-avatar-active`) to decouple the tenant's
identity gradient from the nav-active row background — so the active-tenant row keeps its `[F]` gradient in every
scope while the highlight follows `tenantActive`. `TenantAvatar` gave the initial→`<img>` swap its one home
(row + overview + mobile sheet), which is the seam the future tenant-icon (inbox #58) plugs into.

What didn't go well:
- **A latent test-harness bug surfaced, not a new one.** `mountAt` never stubbed the teleporting PrimeVue
  `Dialog` (mobile sheet), so it rendered the real component without the PrimeVue plugin and its `closeAriaLabel`
  computed threw `Cannot read 'config'` — an **unhandled rejection that failed `npm run check` with exit 1** even
  though all 522 tests "passed". Root cause: the shared mount helper stubbed only `ProfileMenu`; the Dialog error
  was dormant until my new mounts refreshed the computed. Fix: hoist the `DialogStub`, stub `Dialog` **and**
  `Popover` in `mountAt`. **Next time:** stub *every* teleporting PrimeVue overlay (Dialog/Popover/…) in the
  shared mount helper from the start, and treat "Errors: 1" in vitest output as a red build regardless of the
  pass count.
- **A small workflow wobble on coverage timing.** I first added the `tenant-switcher-overview` criterion during
  doc-authoring (pointing at a test that didn't yet assert it), caught that it would make the trust chain claim
  false green, pulled it back, and re-added it in Phase 2 with the real test. Correct end state, but the honest
  move (coverage lands *with* its test) should have been the first move. **Next time:** never write a coverage
  ref ahead of its assertion, even transiently.
- **Under-scoped the parity surface — the user caught it.** I closed the CR with "mobile sheet unchanged",
  reasoning that a popover-in-a-sheet would be fumbly. That conflated the **chevron *mechanism*** (which genuinely
  doesn't fit a sheet) with the **merge *concept*** (which applies everywhere). The mobile sheet still showed the
  exact redundancy the CR set out to kill — the tenant *and* a separate "Gemeinsam" entry — and the active-tenant
  row was even a dead no-op. Reopened as Phase 4. **Next time:** when a change removes a redundancy on one
  surface, walk *every* rendering of that UI (desktop rail + mobile sheet) before declaring done; "unchanged" for
  a parity surface needs a reason that isn't just "the fancy part doesn't fit there".
- **Live gap, documented not hidden:** the bootstrap principal has a single tenant, so the chevron/overview
  couldn't be exercised in the browser — unit tests carry it. A second tenant for the same principal would close
  it; not worth seeding for this change.

Process was clean otherwise: waited for the explicit "los" before coding (the memory note), German conversation
with English code/docs, and reused the legacy UI in-style rather than inventing new markup.
