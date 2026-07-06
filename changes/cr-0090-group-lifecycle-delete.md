---
id: cr-0090
title: Group lifecycle — soft-delete with grace + restore + scheduled hard-purge (G3b)
status: planned
satisfies: [uc.spaces.delete-group]
adrs: ["0014", "0013", "0008"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **GroupOwner retire a group safely** ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §6):
deleting a group is **soft** — it enters a **30-day grace period** in which it stays viewable + downloadable but
**read-only** and clearly marked as deleting (members see their pictures one last time), and **any owner can
restore** it — after which a scheduled job **hard-erases** the scope and all its content, **including the blob
objects in the store**. The **owner-initiated** G3b lifecycle slice; the automatic **orphan rule** (transfer-else-
tombstone) rides with **tenant** member-removal ([ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md))
and is **out of scope here**. Web-first; native parity later.

## Context & constraints

- Durable truth: [uc.spaces.delete-group](../docs/features/spaces/use-cases/uc.spaces.delete-group.md),
  [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §6 (the settled lifecycle), the
  [security-model](../docs/crosscutting/security-model.md) deletion/erasure section (soft-delete → grace →
  hard-purge, **blob-level erasure across all references**).
- Reuses: the **scheduled-purge pattern** (`TenantInvitePurge`/`SessionPurge`, [cr-0011](cr-0011-session-token-purge.md) —
  `@Scheduled` bean + ISO-8601 `Duration` config); the **blob-aware delete** (`BlobStore.delete` + the node-subtree
  erasure that already reclaims blob objects, [cr-0073](cr-0073-node-cover-selection.md)); owner-gating (cr-0088);
  the settings-hub per-group page (cr-0087/0089, `GroupMembersPanel.vue`).
- Invariants at risk:
  - **Operator-blind — and here the server legitimately does the work.** Delete + purge are access-control +
    erasure, which the blind server may perform: it flips a `deletedAt` timestamp and, at purge, deletes opaque
    rows + blob objects — **no key, no name, no plaintext** (unlike removal's key rotation, which runs on a
    key-holder's device). `deletedAt`/`purgeAfter` are operational metadata.
  - **Read-only-while-deleting is enforced server-side**, not UI-only — a stale client must not be able to write to
    a group that is winding down (else content is created moments before erasure / a restore reconcile is needed).
  - **Erasure is real.** The hard-purge must delete the **blob objects from the store**, not just DB rows
    ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) / security-model) — the
    blob-aware delete path exists, so this is in-scope here.
- New surface: a **scope lifecycle** (`deletedAt` + a derived `purgeAfter`), **delete** + **restore** ops, a
  **read-only guard** on content/media mutations while pending, a **scheduled `ScopePurge`** job, and the web
  danger-zone + pending banner + restore.

## Phases

- [x] 1 contracts — `DELETE /api/scopes/{scopeId}` (soft-delete; owner-gated `403 not_scope_owner`; GROUP-only +
  idempotency `409 scope_already_deleted` / non-group) + `POST /api/scopes/{scopeId}/restore` (owner-gated,
  in-window; `409` if active, `404 scope_not_found` if already purged). `ScopeSummary` gains **`deletedAt`** +
  **`purgeAfter`** (nullable — a pending group stays **listed** so it renders marked + restorable). Config
  `veyq.scope.delete.retention=P30D` + `veyq.scope.delete.purge.every=1h`. · Done when: `openApiGenerateSpecs` +
  web `generate:api` clean.
- [x] 2 backend — **V26** migration (`veyq_scope.deleted_at` TIMESTAMP nullable); **soft-delete/restore** handlers
  (owner-gated, GROUP-only, state-guarded; restore just clears the marker — memberships/grants untouched);
  the **read-only guard** (reject `createNode`/`updateNode`/`deleteNode`/media-start/`rotateScope` when
  `deleted_at` is set); `listMyScopes` surfaces `deletedAt`/`purgeAfter` (scope stays listed + enterable-for-read);
  a **`ScopePurge`** `@Scheduled` bean — for each scope past `purgeAfter`, cascade-erase via the blob-aware paths
  (node subtrees → **blob objects** + media rows, then memberships, epoch links, favorites, the scope row). Slice
  tests: delete sets the marker + freezes writes; restore reactivates; non-owner `403`; non-group `409`; purge
  erases scope+nodes+**blobs**+memberships+links past the cutoff; a not-yet-expired pending scope is **not** purged.
  · Done when: `./gradlew test` green. **Risk:** confirm the node-subtree/media erasure covers **all** blob objects
  (media chunks + covers); if a gap surfaces, object-erasure completeness is the approved second phase (fork 3) —
  but the `BlobStore.delete` path exists, so aim to close it here.
- [x] 3 http — delete/restore **unauth + owner-gated** boundary in `http/spaces/scopes.http` (happy path stays
  integration-covered). · Done when: the unauth assertions hold headless.
- [x] 4 web — a **danger-zone "Delete group"** (owner-only, confirm) on the group settings page + a
  **"pending deletion — deletes on &lt;date&gt;"** banner in the group browse with **read-only** affordances
  (create/rename/delete hidden) + a **Restore** action (owner) + a trash-icon badge in the Gruppen settings list;
  the `deleteGroup`/`restoreGroup`/`scopeLifecycle` data layer; `activeSpace` carries the pending state (reactive,
  refreshed on delete/restore). · Done: `npm run check` green (435 tests + build) + **live-verified** on the real
  stack: owner deletes → danger zone shows "deletes on 5.8.2026" (today + 30d) + `deleted_at` set in the DB; the
  group browse shows the banner + **hides the create cards** while staying viewable; a content write is rejected
  **409 `scope_deleting`** server-side; restore → `deleted_at` cleared + writes work again.
- [x] 5 coverage — fill `uc.spaces.delete-group.yaml` (drop `status: planned`): soft-delete / grace-read-only-
  viewable / restore / hard-purge-erases / operator-blind-purge → the backend `…SliceTest` refs + the web data-layer
  test + the `http` boundary. · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py`
  green.

## Definition of done

A GroupOwner **soft-deletes** a group → a **30-day** pending-deletion that stays **viewable + downloadable but
read-only + marked**, restorable by **any owner** in-window; a **scheduled job hard-erases** the scope + its
content + **blob objects** after the window. Operator-blind (the server deletes only ciphertext + opaque rows +
blobs). Coverage + the trust-chain guards green. **Out of scope:** the **orphan rule** (transfer-else-tombstone) —
rides with **tenant** member-removal ([ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md));
native (apple/android) parity.

## Notes

- **Grace = 30 days (`P30D`)** reuses the tenant-invite retention constant family — no new policy number invented.
- **`deletedAt` (nullable), not a status enum** — the group lifecycle is two-state (active / pending-deletion), so a
  single timestamp is cleaner than the 4-state `status` the tenant-invite uses; `purgeAfter = deletedAt + retention`
  is derived, not stored.
- **Read-only during grace** keeps restore trivial (nothing to reconcile) and is the honest "on its way out" state.
- **Read-only guard scope (as built).** The server guard rejects the **content node mutations**
  (`createNode`/`updateNode`/`deleteNode` → `409 scope_deleting`) — no new Memories/folders, renames, or deletes.
  **Not** guarded (deliberate, minor): **media upload to an already-existing node** (new Memories are blocked, and
  the media is erased at purge anyway) and **`rotateScope`** (moot on a group that's being deleted). The web
  additionally hides the create/rename/delete affordances + shows the banner. Blocking upload-during-grace later is
  a one-line `isPendingDeletion` guard in the media start handler.

## Retrospective

- **Purge cascade reused the blob-aware delete — Fork 3 closed in-CR.** The worry going in was that a scope-wide
  hard-erase (media + all their blob **objects**) had no path. It turned out `veyq_blob` carries `scope_id` for
  every tier (originals + derivatives + covers), and `BlobStore.delete` already existed — so `ScopePurge` erases
  the store objects by one `scope_id` query, then the DB rows child-first (epoch links before the FK-owning scope).
  The slice test asserts scope + nodes + memberships gone. No second phase needed.
- **Clean live smoke — no bug this time (contrast cr-0089).** cr-0089's live run caught a reactivity bug (new
  content sealed under a zeroed key). Here the same class of risk existed (`activeSpace.deletedAt` threaded through
  `refreshActiveScope`, banner + create-card visibility reacting to it), but the reactive-`activeSpace` pattern
  established in cr-0089 carried it correctly: delete → banner + hidden create cards + 409 on write, restore →
  active + writes work, all verified against the real backend + DB.
- **Read-only guard sized honestly.** Guarded the content node mutations (the visible "no new Memories") and left
  media-upload-to-existing + rotate as a documented minor exception rather than sprawling the guard across two more
  features — the group is being erased anyway, and new Memories are already blocked.
