---
id: cr-0026
title: Personal scope — generalise scope identity (scopeId + kind) and birth/open a per-tenant personal space
status: in-progress      # planned → in-progress → done (the file is kept as a record)
satisfies: [uc.spaces.personal-scope]   # authored in phase 1
adrs: [0002, 0007, 0009, 0010]
invariants: [operator-blind]
---

## Goal

Give every member a **personal scope per tenant** (ADR 0002) — the first real *scopes structural* increment.
Today the model collapses **scope == tenant** (`MembershipEntity` is keyed by `tenantId`, no scope id/kind,
no "list my scopes"). This CR generalises to a proper **scope identity** (opaque `scopeId` + `kind`
TENANT/GROUP/PERSONAL), then **births and opens** a member's PERSONAL scope. No content (folders/memories)
yet — only that the personal scope **exists and can be opened**. Unblocks groups, the shell's space switcher,
and the content slice.

## Context & constraints

- Decisions: [ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md) (personal scope is
  tenant-bound, one per member per tenant, born at join), [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)
  (membership = role + grant), realising the [scopes & tenancy model](../docs/crosscutting/scopes-and-tenancy.md)
  (opaque scope/node IDs; grant = scope key sealed to identity).
- Builds on [cr-0010](cr-0010-tenant-activation.md) (the TENANT scope + owner grant) and
  [cr-0025](cr-0025-native-hybrid-kem.md) (native `asymWrap`/`asymUnwrap` — required so apple/android can
  seal/open a grant).
- **Operator-blind (the invariant at risk):** the **scope key is generated client-side** and the server
  only ever stores the **ciphertext grant** (`asymWrap(ownIdentityPub, scopeKey, "scope-grant")`) + the
  scope row (id/kind/tenant) + the membership. The server can therefore **not** auto-create a scope (it has
  no key) — birth is **client-driven**, auto-invoked by the client after login/accept if none exists.
- **Reuse the grant primitive** (`asym_scope_grant` vector) — no new crypto/vector.

## Design (settled — generalise scope identity)

- **`veyq_scope`** table: `{ id (uuid, PK), tenant_id, kind (TENANT|GROUP|PERSONAL), created_at }`.
- **`veyq_membership`** gains `scope_id` (the scope this grant opens); keeps `tenant_id` (denormalised for
  Host routing). Membership = `(userId, scopeId, tenantId, role, wrappedGrant)`.
- **Backfill (cr-0010 reconcile):** each existing tenant gets a `veyq_scope` row `kind=TENANT, id=tenant_id`
  (so existing grants still open it); existing memberships set `scope_id = tenant_id`. The activation path
  writes the TENANT scope row + `scope_id`/`kind` going forward.
- **Personal-scope birth — client-driven:** the client generates a random scope key, `asymWrap`s it to its
  **own** identity public key (context `scope-grant`), and `POST /api/scopes` `{ wrappedGrant }`; the server
  mints a `kind=PERSONAL` scope in the **current tenant (from Host)** + the self-membership (`role=OWNER`),
  returns `{ scopeId }`. One personal scope per (user, tenant) — a second call is a no-op/409.
- **List + open:** `GET /api/scopes` → `[{ id, kind, tenantId, wrappedGrant }]` (the caller's own scopes +
  their own grant ciphertext). The client opens a scope by deriving its hybrid secret/public from the vaulted
  identity private bundle and `asymUnwrap`-ing the grant → the scope key, held in memory (a `ScopeVault`).
- **Auto-birth:** after unlock, the client ensures a PERSONAL scope exists in the active tenant (calls
  `POST /api/scopes` once if `GET /api/scopes` shows none) — so "born at join" (ADR 0002) without a user
  action. Covers the existing owner too (no server-side migration of grants needed — the client makes it).

## Phases

- [x] **1 — state.** Authored the `spaces` feature (`docs/features/spaces/_index.md` +
  `use-cases/uc.spaces.personal-scope.md`, Cockburn-lite + PlantUML birth/open sequence + EN/DE), reconciled
  [scopes-and-tenancy](../docs/crosscutting/scopes-and-tenancy.md) (scope identity + personal scope realised)
  and added the [parity matrix](../docs/crosscutting/platform-parity.md) row. **Done:** docs exist, links
  resolve, `plantuml -checkonly` OK, design matches this CR.
- [x] **2 — contracts.** New `contracts/api/scopes.openapi.yaml`: `POST /api/scopes` (`createPersonalScope`,
  `{wrappedGrant}` → `{scopeId,kind}`; 409 `personal_scope_exists`; 404 `tenant_not_found`) + `GET /api/scopes`
  (`listMyScopes` → `[{id,kind,tenantId,wrappedGrant}]`), `ScopeKind` enum, base64-validated grant — same
  idioms as `tenants.openapi.yaml`. **Done:** YAML valid, all `$ref` resolve, operationIds unique; the
  authoritative generator run lands with each surface (backend phase 3 registers it in `openApiGenerateSpecs`).
- [x] **3 — backend.** `V9__scopes.sql` (new `veyq_scope` table; `membership.scope_id` via a fresh-table
  rebuild + `RENAME` — avoids the cross-DB `DROP INDEX` of the V8 `(user,tenant)` unique; TENANT backfill,
  `id=tenant_id`); `ScopeEntity` + `membership.scopeId`; `ScopesStore`/`Handler`/`Resource`
  (`createPersonalScope` + `listMyScopes`, `@Authenticated`, tenant from **Host** — a small resolver reading
  `X-Forwarded-Host`/`Host` → domain → tenant, 404 if unknown, member-gated). Reconciled `TenantStore.activate`
  to write the TENANT scope row + `scope_id`. **Done:** `./gradlew check` green; `ScopesSliceTest` 5/5
  (create→list, one-per-tenant 409, operator-blind: stored grant == sent ciphertext, unknown-host 404,
  non-member 404, unauth 401). *Note: Host→tenant is new here; dev-on-localhost needs a fallback later, a
  follow-up for the web/dev phase.*
- [~] **4 — http. N/A for the happy-path (decided 2026-06-28).** `createPersonalScope` requires an **active
  tenant membership**, which is only born by **invite acceptance** — and the bootstrap secret is **out-of-band
  by design** (in no API response), so the 201 happy-path is **not black-box reachable** via ijhttp. The
  guard contract (401 unauth / 404 unknown-host / 404 non-member) is already proven green by `ScopesSliceTest`;
  the create→open happy-path is covered white-box by the slice test + the web/native e2e (seed harness). No
  `scopes-authed.http` written — it would only duplicate the slice-test guards and still need a live instance.
- [x] **5 — web (data layer).** `features/spaces/scopes.ts`: `listMyScopes` / `ensurePersonalScope`
  (auto-birth, operator-blind — generate scope key, `asymWrap` to own identity, POST only the grant) /
  `openPersonalScope` (`asymUnwrap` the own grant with the vaulted identity). Added the `scopes` spec to
  `generate:api` + `scopesApi()` in `api/client.ts`. **Done:** `npm run check` green (70 tests; new
  `scopes.test.ts` round-trips birth→open with real v1 crypto + mocked API/vault). *Auto-birth call (after
  unlock) + a UI indicator are deferred to the shell — the current web has no home/content UI, and calling
  it on unlock would 404 on dev-localhost until the Host→tenant dev fallback lands.*
- [ ] **6 — apple + android.** The same in each `VeyqClient` (`listMyScopes`/`createPersonalScope`/`openScope`
  via the cr-0025 `asymUnwrap`; a `ScopeVault` holding the opened key). **Done when:** `swift test` +
  `:client:test` green (round-trip: birth → fresh client → open → recovers the scope key); apple iOS +
  android `:app` build.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | personal scope: birth + list + open | Status |
|---|---|---|
| contracts | `POST`/`GET /api/scopes` | ✅ (phase 2) |
| backend | scope identity + create/list + backfill | ✅ (phase 3) |
| http | guards via ScopesSliceTest; happy-path via web/native e2e | **N/A** (phase 4) |
| web | data layer (create/list/open) | ✅ (phase 5; auto-birth wiring + UI → shell) |
| apple / android | data layer (open via cr-0025) | ◻ (phase 6) |
| windows / linux | — | ◻ (when those clients exist) |

## Definition of done

Every member can have a per-tenant personal scope: scope identity is generalised (`scopeId` + `kind`), the
client births it operator-blind (server stores only the ciphertext grant + scope row) and opens it on
web·apple·android, `GET /api/scopes` lists it, the existing owner is covered by auto-birth, and all suites
are green. Content (folders/memories) inside it is the next slice. Then refine whether the first content
lands in the personal or tenant scope.
