---
id: cr-0026
title: Personal scope — generalise scope identity (scopeId + kind) and birth/open a per-tenant personal space
status: planned          # planned → in-progress → done (the file is kept as a record)
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

- [ ] **1 — state.** Author `uc.spaces.personal-scope` (new `docs/features/spaces/` feature + `_index`),
  reconcile [scopes-and-tenancy](../docs/crosscutting/scopes-and-tenancy.md) + [account-key-model](../docs/crosscutting/account-key-model.md)
  (scope identity now realised; personal scope client-birthed) + the [parity matrix](../docs/crosscutting/platform-parity.md).
  **Done when:** docs exist, links resolve, design matches this CR.
- [ ] **2 — contracts.** New `contracts/api/scopes.openapi.yaml`: `POST /api/scopes` (create personal,
  `{wrappedGrant}` → `{scopeId,kind}`; 409 if one exists) + `GET /api/scopes` (list mine →
  `[{id,kind,tenantId,wrappedGrant}]`). **Done when:** spec lints; clients/backend generate.
- [ ] **3 — backend.** `veyq_scope` + `membership.scope_id` Flyway migration (+ TENANT backfill);
  `ScopesResource`/`Handler`/`Store`; create-personal + list-mine (tenant from Host, `@Authenticated`);
  reconcile the cr-0010 activation to write the TENANT scope row + `scope_id`/`kind`. **Done when:**
  `./gradlew check` green incl. a `ScopesSliceTest` (create→list, one-per-tenant, operator-blind: only
  ciphertext stored).
- [ ] **4 — http.** `scopes-authed.http` acceptance (signed create + list happy-paths) with response-handler
  asserts. **Done when:** the suite passes against a fresh seeded instance.
- [ ] **5 — web.** Data layer `createPersonalScope`/`listMyScopes`/`openScope` (asymUnwrap with the vaulted
  identity) + auto-birth after unlock; a minimal Home indicator ("Personal space ready"). **Done when:**
  `npm run check` green + an e2e that opens the personal scope.
- [ ] **6 — apple + android.** The same in each `VeyqClient` (`listMyScopes`/`createPersonalScope`/`openScope`
  via the cr-0025 `asymUnwrap`; a `ScopeVault` holding the opened key). **Done when:** `swift test` +
  `:client:test` green (round-trip: birth → fresh client → open → recovers the scope key); apple iOS +
  android `:app` build.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | personal scope: birth + list + open | Status |
|---|---|---|
| contracts | `POST`/`GET /api/scopes` | ◻ (phase 2) |
| backend | scope identity + create/list + backfill | ◻ (phase 3) |
| http | signed acceptance | ◻ (phase 4) |
| web | data layer + auto-birth | ◻ (phase 5) |
| apple / android | data layer (open via cr-0025) | ◻ (phase 6) |
| windows / linux | — | ◻ (when those clients exist) |

## Definition of done

Every member can have a per-tenant personal scope: scope identity is generalised (`scopeId` + `kind`), the
client births it operator-blind (server stores only the ciphertext grant + scope row) and opens it on
web·apple·android, `GET /api/scopes` lists it, the existing owner is covered by auto-birth, and all suites
are green. Content (folders/memories) inside it is the next slice. Then refine whether the first content
lands in the personal or tenant scope.
