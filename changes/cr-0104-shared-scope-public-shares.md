---
id: cr-0104
title: Public shares in group/tenant scopes — any-member create + removal-deactivation (web vertical)
status: done
satisfies: [uc.sharing.share-shared-scope]
adrs: ["0029", "0030", "0031", "0024", "0025", "0032"]
invariants: [operator-blind-e2ee]
---

## Goal

Lift the **personal-scope-only** restriction on public shares
([uc.sharing.share-shared-scope](../docs/features/sharing/use-cases/uc.sharing.share-shared-scope.md),
[ADR 0029](../docs/decisions/0029-public-viewer-share.md) §6): let **any member** of a **group or tenant** scope
public-share a Memory there (all three built roles), and keep shared-scope content honestly revocable — **removing
a member soft-revokes the shares that member created**, and **any remaining member can re-activate one, which
transfers its ownership**. The crypto + anonymous access path are unchanged (`K_mem` is stable, ADR 0024); only the
**create-gate (scope)** and a **member-removal hook** are new. **Web vertical only; native is a later CR.**

## Context & constraints

- Durable truth: the use-case (the flow + the 5 acceptance criteria) + [ADR 0029](../docs/decisions/0029-public-viewer-share.md)
  §6 (any member may publish; removal deactivates the removed member's shares; re-activation transfers ownership;
  deactivation is the honest-server revocation because `K_mem` cannot be rotated away).
- Reused substrate (not rebuilt): the whole share resource + anonymous resolve + gated blob path (cr-0100/0101/0102);
  the `veyq_share` record already carries **`owner_account`** + `enabled`; the member-removal handlers (group:
  [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) rotation path; tenant: ACL-revoke,
  [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md)); the `sharing.*` capability gate (cr-0103).
- Invariant at risk: **operator-blind-e2ee.** Unchanged — a shared-scope share is the same opaque record; no new
  operator-visible signal, `K_share`/`K_mem` never touch the server.
- **No crypto re-keying.** `K_mem` is stable across rotation (ADR 0024, zero re-encryption), so a public share
  follows new content and cannot be rotated away — the **server-side soft-revoke on removal** is the only (and the
  correct) forward layer. No re-seal, no re-wrap.

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — a **re-activate** operation on `contracts/api/sharing.openapi.yaml` (re-enable a soft-revoked
  share **and** transfer `owner_account` to the caller — e.g. `POST /api/shares/{shareId}/reactivate`, member-gated).
  Update the `createShare` **422 `illegal_scope`** description: it no longer fires for group/tenant (only for a
  non-MEMORY node); a non-member stays a non-enumerable `404`. · Done when: `openApiGenerateSpecs` + web
  `generate:api` clean.
- [x] 2 backend — **widen the create-gate** in `SharingHandler.create`: from "a personal-scope MEMORY the caller
  owns" to "a **MEMORY in a scope the caller is a member of**" (drop the `ScopeEntity.PERSONAL` check; keep the
  `isMember` 404 + the `NodeEntity.MEMORY` 422; keep the `sharing.*` capability gate first). Add **re-activate**
  (`enabled = true` + `owner_account = caller`, gated to a current member of the share's scope). Add a
  **member-removal hook**: when a member is removed from a scope (group + tenant paths), soft-revoke
  (`enabled = false`) every `veyq_share` with `owner_account = removed AND scope_id = scope`. Slice tests: a
  group/tenant member can create + the link resolves; a non-member 404s; removal deactivates exactly that member's
  shares (others untouched) → resolve 404; re-activate flips it back on + transfers ownership; operator-blind
  (record shape unchanged). · Done when: `./gradlew check` green.
- [x] 3 web — widen the Share affordance + `ShareDialog` gate from **personal-scope** to **any scope the caller is a
  member of** (`shareable` computed in `MemoryHomeView` + wherever the personal-scope check lives); add a
  **re-activate** action to the share-management list (for a disabled share the caller may adopt). i18n EN+DE.
  · Done when: `npm run check` green + a **live smoke** (create a share on a group Memory; remove the creator →
  the link 404s; another member re-activates → it works again and is now theirs).
- [x] 4 coverage/docs — fill [uc.sharing.share-shared-scope.yaml](../docs/coverage/features/sharing/use-cases/uc.sharing.share-shared-scope.yaml)
  (backend slice + web unit + live smoke + http), **drop `status: planned`**, mark the use-case **built (web)** in
  [sharing/_index.md](../docs/features/sharing/_index.md); update the three role use-cases' **"personal-scope only"**
  caveats to point at this use-case for the shared-scope extension. · Done when: `validate_coverage.py` +
  `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py` green.

## Definition of done

Any member of a group/tenant scope can public-share a Memory there (all three roles), gated on **scope-membership
AND** the role's `sharing.*` capability — not owner-only, not personal-only. Removing a member **auto-deactivates
every share that member created** in the scope (resolve + gated blob → `404`), independent of the group rotation
the removal also triggers; **any remaining member can re-activate** a deactivated share, which **transfers its
ownership**. `K_mem` is untouched (no re-key, no re-seal); the record stays operator-blind. Guards green
(`./gradlew check`, `npm run check`, the four `docs/` validators, a live smoke). **Out of scope (follow-ups):**
password-protected shares; persisted shares / person-resolution; share notices / tenant terms; **native parity**.

## Retrospective

Landed the full web vertical + backend; `./gradlew check`, `npm run check`, and the four `docs/` validators green.

- **Backend was a small, surgical widen.** The create-gate lost exactly one line (the `ScopeEntity.PERSONAL`
  check) — the `isMember` (404) + `MEMORY` (422) + capability gates already carried the rest; `scopeKind` +
  `ScopeEntity` fell out as dead code. `reactivate` mirrors `revoke` but flips `enabled` back on and transfers
  `owner_account`, gated on `isMember` (not owner-equality). The removal soft-revoke is a new
  `SharingStore.deactivateSharesByOwnerInScope` hooked into **both** removal handlers — the group
  `ScopesHandler.removeMember` (after the membership delete) and the tenant `TenantHandler.removeMember` cascade
  (only the shared-content branch; the own-scope soft-delete keeps the membership + its shares through the grace
  period, matching intent). `K_mem` untouched — no re-key, no re-seal.
- **The risk that wasn't.** ADR 0025 reads as if tenant member-removal were unbuilt; it is in fact live (cr-0091),
  so the tenant hook had a home. Both removal paths funnel through `ScopesStore.removeMembership`, but the hooks
  stayed **per-handler** for exact control over which scopes revoke.
- **Discovery gap → Option A (contract revisit).** The reactivate-**adopt** flow needs a remaining member to *see*
  an orphaned (disabled) share, but `listShares` was owner-scoped. Added a `nodeId` filter: with it, `listShares`
  returns **every** share on that Memory visible to a scope member (membership-gated; `[]` for a non-member), each
  tagged **`mine`**. The manage panel now shows co-members' shares — Re-activate a disabled one (adopt), Revoke
  your own, a muted note for another member's active link. On a shared Memory this is consistent, not a leak.
- **Verification.** Real-HTTP slice tests (`ShareSharedScopeSliceTest`): group member create+resolve; group +
  tenant removal deactivates only that member's shares; reactivate transfers ownership + resolves again;
  non-member reactivate `404`; the node-scoped list with `mine`. Plus the corrected `ShareSliceTest` (a group
  create is no longer `illegal_scope`; only a non-MEMORY is `422`), a web unit round-trip (`share.test.ts`:
  `listSharesFor` server-scopes by `nodeId` + threads `mine`; `reactivateShare`), and an http auth-wall. **The
  full browser live smoke passed** (Familie-Raddatz tenant, two real members): kevin online delivered the tenant
  grant to `other`; the Share affordance appeared on a **tenant** Memory for both; `other` (a non-owner member)
  created a public VIEWER share → resolved `200`; kevin removed `other` → the share auto-deactivated and the link
  `404`'d; in the ShareDialog kevin saw `other`'s share as **"Von einem anderen Mitglied / Abgeschaltet"** with a
  **Reaktivieren** action, re-activated it → the link resolved again `200` and ownership had transferred to kevin
  (`owner=kevin, enabled=1`, the row flipping to "Widerrufen"). Every behavior validated live against the real
  backend + on-device crypto — no bug.
- **Operator-blind intact.** A shared-scope share is the same opaque record; `mine` is derived per request, never
  stored; the server never sees `K_share`/`K_mem`/names/visitor.
- **Deferred (unchanged):** password-protected shares; persisted shares / person-resolution; share notices;
  **native parity**.
