---
id: cr-0110
title: Media delete — soft-delete into a recoverable per-Memory trash + scheduled purge
status: done
satisfies: [uc.memory.delete-media]
adrs: ["0013", "0033"]
invariants: [operator-blind-e2ee]
---

## Goal

Give a signed-in member a **first-party** way to remove a photo/video from a Memory — as a **soft-delete into a
recoverable per-Memory trash**, hard-purged by a scheduled system job after a retention window. The delete
*substrate* already exists ([uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md)'s
`delete_revision` reconcile, cr-0107; the blob-reclaim path of the anonymous share *delete-own*, cr-0102); this CR
is the thin authenticated op + the trash lifecycle + the web UI on top of it.

Refined state this builds on:
[uc.memory.delete-media](../docs/features/memory/use-cases/uc.memory.delete-media.md) (+ its `status: planned`
coverage) and the sharpened *Deletion & erasure* posture in the
[security model](../docs/crosscutting/security-model.md).

## Context & constraints

- **Reversible trash = any-member + audited; irreversible purge = system job.** Any member of the scope may
  **trash** and **restore** (recovery, not destruction); the trashed row records **`deleted_by` + `deleted_at`**;
  the **hard-purge is the scheduled job**, never a member hand-action against another member's content. This is the
  security model's reversible/irreversible split — honored, not overridden. Formal moderation (who may delete
  whose) is a later groups/moderation refine.
- **Reuse the substrate, don't reinvent.** Delete must call **`bumpMediaRevision(nodeId, deleted = true)`** in its
  tx ([ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)) so every open gallery drops the
  tile on its next poll — no new propagation code (the web `upsertItems(delta, mediaIds)` already reconciles the
  id-set). The purge reuses the cr-0102 **derivative → media → blob** FK-ordered reclaim + `blobStore.delete`
  ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)), minus its identity predicate.
- **Soft-delete = a flag, not a row delete.** `deleteMedia` sets `deleted_at`/`deleted_by`; `listNodeMedia` and
  `listNodeMediaIds` filter `deleted_at IS NULL` (so the gallery, the `auto` date/place, and the cover all see the
  shrunken set); `restoreMedia` clears the flag. The **row + blobs survive** in the trash until the purge, so
  restore is a pure flag flip.
- **Guard = any scope member** — the media slice's existing `isMember(userId, scopeId)` gate (as `updateMediaMeta`
  / `commitCoverBlob` use), no new role gate. Non-member ⇒ non-enumerable 404 (operator-blind).
- **Operator-blind.** Delete flips a flag; purge removes opaque blob objects. The **trash state** (flag + actor +
  timestamp) is coarse server-visible operational metadata, now listed in the security model — never content, never
  a key.
- **Self-heal is free.** A trashed cover dangles → the existing placeholder (E2, [uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md));
  `auto` date/place re-materialize from the shrunken list on the next poll — no delete-specific reconcile.
- **Smallest contract surface.** The trash list is a **`state=trashed` filter on `listNodeMedia`**, not a new op;
  delete + restore are the two new endpoints.
- **Non-goals:** cross-scope / global trash (per-Memory tab only); a per-item "delete forever" UI (purge is the
  system job); formal shared-scope moderation; bulk/multi-select delete; native heads (web-first,
  [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md); their own CRs).
- **Verify:** `./gradlew check` (backend slice) + `cd web && npm run check`; the three trust-chain guards green; a
  live browser smoke on `veyq.localhost` (trash → tile drops + trash tab + restore + the Delete-key culling flow;
  operator-blind at the DB: only `deleted_at`/`deleted_by`, ciphertext intact until purge).

## Phases

- [x] 1 contracts — [media.openapi.yaml](../contracts/api/media.openapi.yaml): add **`deleteMedia`**
  (`DELETE /api/scopes/{scopeId}/media/{mediaId}` → 204/401/404, `bearerAuth`), **`restoreMedia`**
  (`POST /api/scopes/{scopeId}/media/{mediaId}/restore` → 204/401/404), and a **`state`** query param
  (`active` default | `trashed`) on **`listNodeMedia`** so the trash tab lists trashed rows with `deleted_by` +
  `deleted_at`. Regen the web client. · Done when: the generated client carries the three; `npm run generate:api`
  clean.
- [x] 2 backend — a migration adds **`deleted_at`** (nullable) + **`deleted_by`** to `veyq_media`;
  `MediaStore.deleteMedia`/`restoreMedia` (soft flag + `bumpMediaRevision(deleted = true)` in-tx), `listNodeMedia`
  /`listNodeMediaIds` filter `deleted_at IS NULL` + a trashed-list variant; `MediaHandler.deleteMedia`/`restoreMedia`
  behind the existing `isMember` guard; `MediaResource` the two endpoints + the `state` filter; a **`@Scheduled`
  `MediaTrashPurge`** bean (reusing the cr-0102 derivative→media→blob reclaim + `blobStore.delete`) driven by a
  **`veyq.media.trash-retention`** property (default `P30D`). Boundary `.http` (401 on delete/restore). · Done when:
  `./gradlew check` green; a `MediaTrashSliceTest` covers trash → excluded-from-list + `delete_revision` bump →
  restore → re-listed → purge-past-retention hard-deletes rows + reclaims blobs; the isMember/404 negatives.
- [x] 3 web — `media.ts` `deleteMedia`/`restoreMedia`/`listTrash` wrappers; **MediaLightbox** trash button +
  **Delete/Backspace** hotkey (respecting the cr-0109 `suspendKeys`) → trash current + advance + an **undo toast**
  (restore) + optimistic grid removal; a **Trash tab** in `MemoryDetail` (beside Gallery/Chapters) listing trashed
  media (thumbnail + `deleted_by` + when) with Restore + an empty state. i18n EN+DE (Neutral-first). · Done when:
  `npm run check` green; unit tests cover the lightbox delete/hotkey/undo + the trash tab restore; a live smoke.
- [x] 4 coverage — fill [uc.memory.delete-media.yaml](../docs/coverage/features/memory/use-cases/uc.memory.delete-media.yaml)
  with the real backend/web/http test refs, **drop `status: planned`**, keep apple/android as web-first gaps. ·
  Done when: `validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Soft-delete media → per-Memory trash + restore + scheduled purge | Status |
|---|---|---|
| contracts / backend | delete/restore ops + `state` filter; `deleted_at`/`deleted_by`; `MediaTrashPurge` | ◻ this CR |
| web | lightbox delete + Delete-key + undo; Trash tab + restore | ◻ this CR |
| apple / android | native gallery/delete/trash not built yet | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A signed-in member can remove a photo from a Memory in the lightbox (trash button or Delete/Backspace) → it leaves
every open gallery live (`delete_revision`), an undo toast + a per-Memory Trash tab restore it for the retention
window, and a scheduled job hard-purges it (rows + blobs + store objects) afterwards. Any scope member may
trash/restore; the trashed row is audited (`deleted_by`/`deleted_at`); the purge is the system job. A trashed cover
falls back to the placeholder and `auto` date/place self-heal — no new reconcile code. Operator-blind untouched
(a flag flip + opaque-blob purge). `./gradlew check` + `npm run check` + the conformance test + the three
trust-chain guards green; `uc.memory.delete-media` coverage filled and `status: planned` dropped; the live smoke
confirmed at the DB.

## Retrospective

Delivered end-to-end as scoped: contracts → backend (V34 `deleted_at`/`deleted_by`, soft-delete/restore/trashed-
list, `MediaTrashPurge`) → web (media.ts wrappers, lightbox trash button + Delete/Backspace culling + undo
snackbar, per-Memory Trash tab). The substrate paid off — a first-party delete really was thin: reuse the cr-0107
`bumpMediaRevision(deleted=true)` reconcile (no client propagation code) and the cr-0102 derivative→media→blob
reclaim (generalised `deleteBlobRow`, shared by cover + trash purge). `./gradlew check` + `npm run check` + the
three trust-chain guards green; a full live smoke on `veyq.localhost` confirmed the whole loop (trash from the
lightbox drops the tile + advances; Trash tab lists it with the date + restores; Delete-key culling + undo
snackbar; multi-item restore; operator-blind at the DB).

What didn't go well:

- **I broke the user's running dev backend during the live smoke.** The smoke needed the new backend (V34 + the
  new endpoints), but the user's IntelliJ `quarkusDev` on 8080 had the old code. To avoid touching it I started a
  **second** `quarkusDev` on 8082 **in the same `backend/` directory** — not realising the Quarkus
  class-change-agent on the 8080 instance watches the shared `backend/build/`, so my build hot-reloaded my new
  entities/migration/beans into the running 8080 instance and left it 500ing. Root cause: two `quarkusDev` on one
  project dir share `build/` and fight via hot-reload. **Next time:** never run a parallel `quarkusDev` in the same
  repo while the user's is up — to smoke new backend code, either restart the user's backend (or ask them to), or
  run the throwaway instance from an **isolated git worktree** (separate `build/`). Recorded as a machine-local
  memory.
- **The undo snackbar's 6-second timer kept expiring between preview-tool round-trips**, so it read as "not
  rendering" until I captured it inside a single delayed `preview_eval`. Not a bug — a lesson that transient UI
  needs same-eval capture, not sequential round-trips.
- **Actor-name in the trash tab deferred.** `deleted_by` is stored + returned (the audit), but the tab shows only
  the *when*; a friendly "trashed by X" needs a username resolve (disclosed in `gaps.web`), reasonable to defer for
  a personal-scope-dominant V1.

Process otherwise clean: waited for the explicit "los" before coding; German conversation, English code/docs;
neutral-first DE strings (the agreed gendering convention) for the new trash/undo copy; cheap text-based smoke
probes before the one screenshot.
