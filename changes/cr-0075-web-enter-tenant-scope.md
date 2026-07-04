---
id: cr-0075
title: Web — enter the shared Tenant space + switch Personal ↔ Tenant (token-less default flip)
status: done
satisfies: [uc.spaces.switch-spaces, en.spaces.tenant-scope]
adrs: ["0015", "0019"]
invariants: [operator-blind-e2ee]
---

## Goal

Make the shared **`TENANT`** scope **enterable** in the web shell and let a member switch **Personal ↔ Tenant**.
The tenant scope already exists server-side — born cold at activation with the owner's grant
([ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md), [cr-0010](cr-0010-tenant-activation.md)) — and
`GET /api/scopes` already returns it (`ScopeKind.Tenant` is in the generated client); **only the client entry is
missing**. Per [ADR 0019](../docs/decisions/0019-token-less-tenant-home.md) the **token-less** fragment becomes
the Tenant space (the shared commons is home): `/` · `/#urlaube` for the tenant, `/#~me` · `/#~me/urlaube` for
Personal, `/#@<id>` for a group (later). When a member holds **no** tenant grant, the shell **falls back to
Personal** ([en.spaces.tenant-scope](../docs/features/spaces/enablers/en.spaces.tenant-scope.md)).

**Web only. No new contracts, backend, or crypto** — the tenant scope, its row, and the owner grant already
exist ([cr-0010](cr-0010-tenant-activation.md)); this CR opens an existing grant client-side (the same
hybrid-KEM `asymUnwrap` the personal scope already uses) and generalises the shell's scope selection. Contracts
+ backend are untouched. **Groups and any sharing / tenant member-invite are explicit non-goals.**

## Context & constraints

- **Refined state this builds on:** [en.spaces.tenant-scope](../docs/features/spaces/enablers/en.spaces.tenant-scope.md)
  (open + enter, **no birth**), [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md)
  (the updated space list + URL grammar + reactive re-entry), [ADR 0019](../docs/decisions/0019-token-less-tenant-home.md)
  (token-less = tenant, revising the default left open by [ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md)).
- **No birth — the load-bearing constraint.** `enterPersonalSpace()` **auto-births** via `ensurePersonalScope()`
  ([web/src/app/activeSpace.ts](../web/src/app/activeSpace.ts),
  [web/src/features/spaces/scopes.ts](../web/src/features/spaces/scopes.ts)). The generalised open path must
  **never** mint a `TENANT` scope — minting client-side would fork the single shared root
  ([ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md)). Tenant is **open-only**; absence → the
  Personal fallback, not a `POST`.
- **The grammar today defaults empty → Personal.** [web/src/features/memory/location.ts](../web/src/features/memory/location.ts)
  has `PERSONAL_SCOPE = '~me'` and `parseHash` defaults an empty/bare fragment to `~me`; `@group` is parsed but
  unresolved. This CR flips the **token-less** default to the tenant and keeps `~me`/`@` explicit. Slugs can
  never collide with the `~me`/`@` tokens — `slugify` emits only `[a-z0-9-]`, so `~`/`@` never lead a folder
  slug (no disambiguation needed).
- **Operator-blind unchanged.** The fragment still never reaches the server ([ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md));
  opening the tenant scope is a client-side `asymUnwrap` of a grant that already exists; the server sees only the
  opaque scope row it already stored. No new bytes cross the wire.
- **Re-entry must reset per-scope state.** Switching the leading token re-enters the scope: open its key, then
  **reset the tree + favorites** ([web/src/features/memory/treeStore.ts](../web/src/features/memory/treeStore.ts)
  `resetTree()`, [web/src/app/favoritesStore.ts](../web/src/app/favoritesStore.ts) `clearFavorites()`) and reload
  for the new scope — no stale tree/favorites bleed across scopes. Navigating *within* a scope (segment-only
  change) must **not** re-enter. There is **no scope-change watcher today** — `ready()` runs once on mount
  ([web/src/views/MemoryHomeView.vue](../web/src/views/MemoryHomeView.vue)); this CR adds the reactive re-entry.
- **Reuse legacy UI, swap the data layer** ([docs/ai/README.md](../docs/ai/README.md)). The sidebar space list
  already renders the Personal entry ([web/src/components/app/AppSidebar.vue](../web/src/components/app/AppSidebar.vue),
  a hardcoded `RouterLink` to `#~me`); the Tenant entry is a **sibling row in the same markup**, not new UI. Its
  label is the **tenant name** (from `listMyTenants()` membership metadata — already fetched for the tenant
  switcher; no sealed name to decrypt). The active space gets the existing active-link styling.
- **Non-goals:** groups (`@<id>` stays parsed-but-unresolved as today), any sharing, **tenant member-invite /
  grant distribution** (so non-owners keep hitting the Personal fallback — documented, not a bug), and the
  native heads (their own CRs, web-first per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)).
- **Verify:** `cd web && npm run check` (typecheck + vitest + build); the crypto conformance test + the three
  trust-chain guards stay green; a **live browser smoke** (Phase 5).

## Phases

- [x] 1 web — **grammar flip in `location.ts`.** Introduce the tenant as the token-less scope: a `TENANT_SCOPE`
  marker so `parseHash('')`/`parseHash('#')` → the tenant (not `~me`), `parseHash('#urlaube')` → tenant +
  `['urlaube']`, `#~me…` → personal, `#@x…` → group (parsed, unresolved as today). `buildHash(TENANT, [])` → `''`
  (root `/`), `buildHash(TENANT, ['urlaube'])` → `#urlaube`; `~me`/`@` forms unchanged. Make `hashForNode`
  **scope-aware** (build the fragment for the *active* scope, token-less for tenant). · Done when: `npm run
  check` green; `location.test.ts` covers token-less→tenant, `#~me`→personal, tenant round-trips, and that a
  slug never parses as a scope token.
- [x] 2 web — **generalised open + enter with fallback.** In `scopes.ts` add `openScope(kind)` (generalise
  `openPersonalScope`: find the scope of `kind`, `asymUnwrap` its grant → key; **no birth**) — keep
  `ensurePersonalScope` the *only* birth path, personal-only. In `activeSpace.ts` add `enterScope(token)` (open
  the token's scope, set `activeSpace.scopeId/tenantId`, hold the key non-reactively, **`resetTree()` +
  `clearFavorites()` + reload**), and the **Personal fallback**: entering the tenant with no `TENANT` grant
  resolves to Personal and flags the tenant unavailable. · Done when: `npm run check` green; `scopes.test.ts`
  covers `openScope(TENANT)` asymUnwraps to a 32-byte key **and never POSTs**; `activeSpace.test.ts` covers
  enter-tenant (ready + scopeId + key), no-grant→Personal fallback, and that re-entry clears tree + favorites.
- [x] 3 web — **shell wiring.** `MemoryHomeView.vue`: replace the one-shot `ready()`/personal assumption with a
  reactive **re-entry on leading-token change** (segment-only changes do not re-enter); the breadcrumb root
  label comes from the **active scope** (tenant name vs. `shell.personal`), not the hardcoded
  `t('shell.personal')`. `AppSidebar.vue`: render the **Tenant space** entry (label = tenant name, link = `/`)
  beside Personal (`#~me`), with the active-space highlight; keep the tenant *switcher* (full-nav) as is. i18n
  EN+DE for any new shell label (e.g. `shell.shared` / tenant-name fallback) in
  [web/src/i18n/index.ts](../web/src/i18n/index.ts). · Done when: `npm run check` green; a component/unit test
  covers the sidebar listing Tenant + Personal with the active one highlighted, and the breadcrumb root
  reflecting the active scope.
- [x] 4 coverage — add the new acceptance criteria to
  [uc.spaces.switch-spaces.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.switch-spaces.yaml):
  **token-less-tenant-default** (empty fragment → tenant), **personal-tenant-switch-resets** (a leading-token
  flip re-enters + resets tree/favorites), **no-tenant-grant-fallback** (no grant → Personal home), each mapped
  to the Phase 1–3 tests; note apple/android as gaps (web-first). · Done when: `python3
  scripts/validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.
- [x] 5 verify (browser smoke) — live stack (MariaDB + `quarkusDev` + `npm run seed:dev` + Vite): the seeded
  **owner** (holds the tenant grant) signs in → unlock → lands in the **Tenant** home (`/`) → switches to
  **Personal** (`#~me`) and back → the tree + favorites reset each way, no stale bleed → deep-links `/#urlaube`
  and `/#~me/urlaube` resolve. A member **without** a tenant grant lands in **Personal** with the Tenant entry
  unavailable. · Done when: confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Enter the Tenant space + Personal ↔ Tenant switching (token-less default) | Status |
|---|---|---|
| web | `location.ts` flip + `openScope(kind)`/`enterScope` + sidebar Tenant entry | ◻ this CR |
| apple / android | native tenant-space entry + switcher parity | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A granted member opens the web app and lands in the shared **Tenant** space at `/`, sees a **Tenant** entry
(labelled with the tenant name) beside **Personal** in the sidebar, and switches **Personal ↔ Tenant** by the
leading fragment token — each switch **re-enters** the scope (opens its key) and **resets the tree + favorites**,
with no stale bleed. A member with no tenant grant **falls back to Personal**. The tenant scope is **opened,
never minted** (no client birth). Operator-blind unchanged — the fragment never leaves the browser and no new
bytes cross the wire; contracts + backend untouched. `npm run check` + the conformance test + the three
trust-chain guards green; `docs/coverage/` updated for `uc.spaces.switch-spaces` with
`python3 scripts/validate_coverage.py` green; the live smoke confirmed with screenshots.

## Retrospective

Delivered as scoped — web-only, no contracts/backend/crypto touched. The grammar flip centred on a
`TENANT_SCOPE = ''` marker (absence of a leading token *is* the tenant selector), which kept `parseHash` /
`buildHash` symmetric and made `hashForNode` / `breadcrumbOf` scope-aware by threading the active scope token
rather than defaulting. `openScope(kind)` generalised the open path and returns `{ scopeId, tenantId, kind,
key }` so `enterScope` needs no second `listMyScopes` round-trip; `openPersonalScope` stayed as a thin wrapper
for the e2e harnesses. The Personal fallback lives entirely in `enterScope` (tenant is open-only — no client
birth), with a `tenantUnavailable` flag for the shell. Re-entry is a `watch(currentScope)` on the **leading
token** so segment-only navigation doesn't re-enter; the reset (`resetTree` + `clearFavorites`) sits in
`enterScope` while the reload stays in the view, keeping `activeSpace` free of network deps.

Sidebar highlight needed `RouterLink custom` slots — both space entries share the `/` path (only the fragment
differs), so RouterLink's path-based `router-link-active` would light both; driving the active class off the
parsed leading token fixed it without a CSS change.

**Live smoke reality:** the running instance is the `bootstrap:user` principal (`kevin@veyq.test` on
`Familie Raddatz` / `veyq.localhost`, creds in `.run/.env`), **not** the older `seed:dev` `127.0.0.1` tenant —
logging in against `127.0.0.1` gave a `login/verify` 401 until we switched host. Verified: the owner lands in
the Tenant home at `/` (breadcrumb + sidebar labelled *Familie Raddatz*, highlighted), flips to Personal on
`#~me` (breadcrumb → *Persönlich*, highlight moves) and back, with a clean re-entry each way. Two gaps in the
live run (both covered by unit tests, not blockers): the bootstrap tenant is **empty**, so deep-link-to-content
(`/#urlaube`, `/#~me/urlaube`) couldn't be shown resolving to a folder; and there is no second, grantless member
seeded, so the **no-tenant-grant → Personal fallback** was confirmed only by `activeSpace.test.ts`, not live.
