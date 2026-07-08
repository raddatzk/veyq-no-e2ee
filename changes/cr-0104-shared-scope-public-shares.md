---
id: cr-0104
title: Public shares in group/tenant scopes — any-member create + removal-deactivation (web vertical)
status: planned
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

- [ ] 1 contracts — a **re-activate** operation on `contracts/api/sharing.openapi.yaml` (re-enable a soft-revoked
  share **and** transfer `owner_account` to the caller — e.g. `POST /api/shares/{shareId}/reactivate`, member-gated).
  Update the `createShare` **422 `illegal_scope`** description: it no longer fires for group/tenant (only for a
  non-MEMORY node); a non-member stays a non-enumerable `404`. · Done when: `openApiGenerateSpecs` + web
  `generate:api` clean.
- [ ] 2 backend — **widen the create-gate** in `SharingHandler.create`: from "a personal-scope MEMORY the caller
  owns" to "a **MEMORY in a scope the caller is a member of**" (drop the `ScopeEntity.PERSONAL` check; keep the
  `isMember` 404 + the `NodeEntity.MEMORY` 422; keep the `sharing.*` capability gate first). Add **re-activate**
  (`enabled = true` + `owner_account = caller`, gated to a current member of the share's scope). Add a
  **member-removal hook**: when a member is removed from a scope (group + tenant paths), soft-revoke
  (`enabled = false`) every `veyq_share` with `owner_account = removed AND scope_id = scope`. Slice tests: a
  group/tenant member can create + the link resolves; a non-member 404s; removal deactivates exactly that member's
  shares (others untouched) → resolve 404; re-activate flips it back on + transfers ownership; operator-blind
  (record shape unchanged). · Done when: `./gradlew check` green.
- [ ] 3 web — widen the Share affordance + `ShareDialog` gate from **personal-scope** to **any scope the caller is a
  member of** (`shareable` computed in `MemoryHomeView` + wherever the personal-scope check lives); add a
  **re-activate** action to the share-management list (for a disabled share the caller may adopt). i18n EN+DE.
  · Done when: `npm run check` green + a **live smoke** (create a share on a group Memory; remove the creator →
  the link 404s; another member re-activates → it works again and is now theirs).
- [ ] 4 coverage/docs — fill [uc.sharing.share-shared-scope.yaml](../docs/coverage/features/sharing/use-cases/uc.sharing.share-shared-scope.yaml)
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

_(to be filled at close)_
