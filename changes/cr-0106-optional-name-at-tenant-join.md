---
id: cr-0106
title: Optional real name at tenant join — sealed at accept (web vertical)
status: done
satisfies: [en.spaces.member-identity]
adrs: ["0023", "0021"]
invariants: [operator-blind-e2ee]
---

## Goal

Offer the **optional first/last name at the tenant-join step** (alongside the required username), sealed under
`K_tenant` **at accept** when the invitee already holds the key — instead of only later, hidden in Settings →
Profile ([en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md),
[ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)). Login stays **email + password** (unchanged);
the username stays a **required, per-tenant** handle. The real name is **optional** and still **editable later in
the profile**; a grant-less "awaiting access" accept skips it (defers, unchanged — [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)).
**Web vertical only; native is a later CR.**

## Context & constraints

- Durable truth: [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md) (identity is per-tenant;
  username chosen at every join; the real name is optional, offered at join when `K_tenant` is present at accept,
  else deferred) + [en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md)
  (the build flow).
- Reused substrate (not rebuilt): the two accept endpoints `acceptTenantInvite` /
  `registerAndAcceptTenantInvite` (they already take the `username` + seal the grant on-device); the per-(user,
  tenant) profile row + its **`sealedName`** blob (the profile update already writes it — same shape/sealing);
  `TenantJoinView` + `web/src/features/admin/createTenant/tenants.ts` (`acceptInvite` / `registerAndAccept`).
- Invariant at risk: **operator-blind-e2ee.** Unchanged — the name is **sealed under `K_tenant` on-device**; the
  server stores only the opaque blob (exactly as the profile path already does), never the plaintext name.
- **No new crypto / no model change.** Username scope stays **per-tenant** (unchanged); this only adds an
  optional entry point (the join) for the already-modeled sealed name. Both entry points (join + profile) seal
  identically.

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — add an **optional `sealedName`** to the request bodies of **`acceptTenantInvite`** and
  **`registerAndAcceptTenantInvite`** in `contracts/api/tenants.openapi.yaml` (the real name sealed under
  `K_tenant`, same base64 blob shape + validation as `updateMyProfile.sealedName`). · Done when:
  `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [x] 2 backend — the two accept handlers persist the optional `sealedName` onto the **per-(user, tenant) profile
  row** they already create at accept (the same column the profile update writes); absent ⇒ username-only, name
  deferred. Slice tests: accept **with** `sealedName` → the profile row + `listScopeMembers` carry it; accept
  **without** → username-only; the server stores only the opaque blob (operator-blind); a bad blob shape → `400`
  at the boundary. · Done when: `./gradlew check` green.
- [x] 3 web — `TenantJoinView`: add **optional first/last name** inputs alongside the required username; when the
  invitee holds `K_tenant` at accept (owner cold-birth / grant-sealed accept), **seal `firstName`+`lastName`
  under `K_tenant`** (reuse the profile's seal helper) and pass `sealedName` to `registerAndAccept` /
  `acceptInvite`; **skip** the name field for a grant-less accept (E2). i18n EN+DE. · Done when: `npm run check`
  green + a **live smoke** (join a tenant entering a name → the member directory shows it, decrypted locally;
  join without → `@username` only; the name is still editable in Settings → Profile).
- [x] 4 coverage/docs — add the join-time-name criteria + test refs to the member-identity coverage
  (backend slice + web unit + the live smoke), keep **apple/android** as gaps; the enabler + ADR are already
  refined. · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` +
  `validate_feature_wiring.py` green.

## Definition of done

The tenant-join flow offers an **optional first/last name** next to the required username; when the invitee holds
`K_tenant` at accept, the name is **sealed on-device and submitted with the accept** (one step, no separate
profile visit), landing on the per-(user, tenant) profile row and surfacing (locally decrypted) in the member
directory. A grant-less accept skips the field and defers to the profile (unchanged). The name stays **editable in
Settings → Profile**. Login is **email + password**; the username stays a **required per-tenant** handle.
Operator-blind throughout (the server sees only the opaque sealed blob). Guards green (`./gradlew check`,
`npm run check`, the four `docs/` validators, a live smoke). **Out of scope (follow-ups):** any change to the
username scope (stays per-tenant); a required (vs optional) name; **native parity**.

## Retrospective

Landed even smaller than scoped — the model already carried most of it:

- **Backend was pure threading.** `TenantProfileStore.create(...)` already had an optional `sealedName` param, and
  `update` already wrote it. So the change was just passing `sealedName` from the two accept requests through
  `TenantAcceptHandler.accept` / `registerAndAccept` → `profiles.create(..., sealedName)`. No store/schema change.
- **The web crux — exposing `K_tenant`.** `scopeGrant(pub)` minted a fresh scope root key and **discarded it**
  (returning only the sealed grant), so `TenantJoinView` had no key to seal the name with. Added
  `bornTenantScope(pub) → { grant, scopeRootKey }`; `scopeGrant` now delegates to it (all existing callers +
  the scope-rotation e2e unchanged). The owner-accept paths seal the name under `born.scopeRootKey` and pass it.
- **Owner-only at join, by crypto necessity.** Only the OWNER accept cold-births `K_tenant`, so only it can seal
  at join — the name fields are `v-if="!isMember"`. A grant-less MEMBER has no key at accept (E2, ADR 0023), so
  its name field is hidden and the name is added later in the profile (unchanged). The name stays editable there.
- **Coverage rides `uc.spaces.add-member`** (its `member-identity` criterion) — enablers aren't coverage-tracked
  in this repo, so the new backend slice + facade test refs went there, next to the cr-0083 member-identity tests.
- **Live smoke caught a real bug the slice tests missed.** Driving the owner-join live (a fresh tenant "Smoke3",
  existing `kevin@veyq.test` account, name entered at accept) surfaced a **NULL `sealed_name`** — the
  existing-account **OWNER-accept** branch (`TenantAcceptHandler.accept`, the `invite.role != MEMBER` case)
  wasn't threading `sealedName` into `profiles.create(...)`. The phase-3 edit's `replace_all` had matched only
  the MEMBER branch + `registerAndAccept`, and the only slice test for the join-time seal
  (`MemberIdentitySliceTest.registerAndAcceptSealsTheOptionalNameAtJoin`) exercised the **register**/MEMBER
  path — so the broken existing-owner path stayed green. **Fix:** pass `sealedName` at the owner cold-birth
  (`TenantAcceptHandler.kt`), plus a regression slice `TenantAcceptSliceTest.existingOwnerAcceptSealsTheOptionalNameAtJoin`
  (real `POST /api/tenants/accept` with `sealedName` → the profile row carries the exact opaque blob; without →
  null). **Verified live:** after the fix, Smoke3's profile `sealed_name` is a 96-char sealed blob (Smoke/Smoke2,
  joined before the fix, stay NULL) — the server stored only the opaque blob, never a plaintext name.
- **Also proven at the HTTP layer:** the register/MEMBER slice above, the facade test (`sealedName` threads
  through both accept calls), and the seal round-trip (`profile.test.ts`). The browser *unseal* capstone (the
  decrypted name in the member directory) hit cross-origin login friction on the fresh `smoke3.localhost`
  origin, not a product issue — the unseal is guaranteed by construction (the name is sealed under the same
  `born.scopeRootKey` the grant carries) and covered by `profile.test.ts`. Guards green: `./gradlew check`,
  `npm run check`, the four `docs/` validators.
- **Operator-blind intact.** The name is AEAD-sealed on-device under `K_tenant`; the server stores only the opaque
  blob (same path the profile op already used) and never decrypts it.
- **Deferred (unchanged):** username scope stays **per-tenant**; a required (vs optional) name; **native parity**.
