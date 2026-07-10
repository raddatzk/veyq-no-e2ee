---
id: cr-0111
title: Media trash — Trash-tab redesign + member-expedited purge, and the upload/delete reconcile fixes
status: done
satisfies: [uc.memory.delete-media, uc.memory.upload]
adrs: ["0013", "0033"]
invariants: [operator-blind-e2ee]
---

## Goal

Follow-up to cr-0110 (media trash) + cr-0074 (optimistic upload tiles), from live-testing feedback. Three defect
fixes and one redesign:

1. **Upload optimistic tiles reconcile per-file, not all-at-once.** They only reconciled via the batch
   `onComplete → load()`, so every done tile lingered grayed with a full progress ring until the *whole* batch
   finished, then all flipped together. Fix: a **per-file `onFileDone` hook** in the upload queue drives a targeted
   live poll ([ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)) so each tile reconciles
   the moment ITS upload settles.
2. **Trashed media stop reappearing in the gallery grid.** Trashing two photos in quick succession re-listed the
   still-active second one via a poll that fired before its delete committed (the optimistic removal kept no
   record). Fix: a client-side **`pendingDelete` tombstone** filtered out of the merge/poll paths until the server
   confirms removal.
3. **The undo snackbar's action button** rendered as a white box (the light `.veyq-text-button` surface + white
   text on the dark snackbar). Fix: transparent background + white text.
4. **Trash-tab redesign** (the user's spec): the **same justified `MediaGrid`** as the gallery (dimmed tiles), a
   per-tile **checkbox** (batch-select) + a **3-dot menu** (Restore / Delete forever) + a batch toolbar — dropping
   the per-tile restore button + date. **Delete forever** is a new **member-expedited purge** — hard-deleting an
   **already-trashed** item now, bypassing the retention window — which relaxes the cr-0110 security-model line.

## Context & constraints

- **Refined state this builds on:**
  [uc.memory.delete-media](../docs/features/memory/use-cases/uc.memory.delete-media.md) (main-flow step 4 = the
  redesigned Trash tab; the *Purging is irreversible* note now allows a member to expedite an already-trashed
  item) + [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) (`optimistic-in-grid-tiles` now
  states the per-file reconcile).
- **Security-model relaxation (the user's explicit choice).** cr-0110 sharpened *Deletion & erasure* to "purge is
  the system job, not a member hand-action". cr-0111 relaxes it: **purge = the scheduled job OR a member
  expediting an item already in the trash** — the item was already reversibly removed, so this only brings the
  inevitable purge forward. A member still **cannot** purge a **live** item in one step (destruction is always
  trash → purge). The `Deletion & erasure` line is edited in place.
- **`purgeMedia` reuses the reclaim, adds nothing structural.** `DELETE`s derivative → media → blob rows + the
  store objects (the generalised `deleteBlobRow`, same as the scheduled `MediaTrashPurge` and the cr-0102 delete-
  own, [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)). Guard = `isMember`; only a
  **trashed** media (`deleted_at != null`) is purgeable — a live/unknown one is a non-enumerable 404. No migration,
  no revision bump (a trashed row is already off every list). Operator-blind — opaque blob objects removed.
- **Reuse the shared grid — no new layout.** The Trash tab renders through the same [MediaGrid](../web/src/features/memory/components/MediaGrid.vue)
  the gallery + share viewer use; a trashed tile IS the `.gallery-tile` (dimmed) with a select-check + a 3-dot menu
  overlay. The purge is guarded by a confirm dialog (it is irreversible).
- **The reconcile fixes are pure client work.** The backend filter + revision model are already correct
  (cr-0107/0110, slice-tested); `onFileDone` + `pendingDelete` live in the queue + `MemoryDetail`.
- **Non-goals:** surfacing the trasher's display name (needs a username resolve, deferred); a global/cross-scope
  trash; native heads (web-first, [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)).
- **Verify:** `./gradlew check` + `cd web && npm run check`; the three trust-chain guards green; a live browser
  smoke on `veyq.localhost` (from an **isolated worktree/single** backend — no parallel `quarkusDev`).

## Phases

- [x] 1 contracts — [media.openapi.yaml](../contracts/api/media.openapi.yaml): add **`purgeMedia`**
  (`POST /api/scopes/{scopeId}/media/{mediaId}/purge` → 204/401/404). Regen web client.
- [x] 2 backend — `MediaStore.purgeMedia` (hard-delete a trashed media, reusing `deleteBlobRow`; null on
  live/unknown), `MediaHandler.purgeMedia` (isMember guard + best-effort store purge), `MediaResource.purgeMedia`.
  · Done when: `./gradlew check` green; `MediaTrashSliceTest` covers purge-now (row gone + restore 404), purge-a-
  live-media 404, purge-by-non-member 404.
- [x] 3 web — **(a) bug fixes:** `uploadQueue` `onFileDone` (fired after `status='done'`) + `MemoryDetail` wires it
  to `pollMedia`; a `pendingDelete` tombstone in `MemoryDetail` filtered in `upsertItems`/`mergeItems` (added on
  trash, cleared on server-confirm/undo/restore); the `.memory-undo-action` transparent-bg fix. **(b) redesign:**
  `media.ts` `purgeMedia` wrapper; the Trash tab rebuilt on `MediaGrid` with per-tile checkbox + a `Menu`
  (Restore / Delete forever) + a batch toolbar + a purge-confirm `Dialog`. i18n EN+DE (Neutral-first). · Done when:
  `npm run check` green; `uploadQueue.test.ts` covers per-file `onFileDone`; the existing suites stay green.
- [x] 4 coverage — [uc.memory.delete-media.yaml](../docs/coverage/features/memory/use-cases/uc.memory.delete-media.yaml)
  (`member-expedited-purge` criterion + the redesigned `per-memory-trash-tab` text + `gaps.web`) and
  [uc.memory.upload.yaml](../docs/coverage/features/memory/use-cases/uc.memory.upload.yaml) (the per-file reconcile
  clause). · Done when: `validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` green.
- [x] 5 verify (browser smoke) — a single/worktree backend on `veyq.localhost`: per-file upload reconcile; trashing
  two in a row leaves both out of the grid; the undo button is visible; the Trash tab shows the grid + checkbox +
  3-dot menu; Restore + Delete-forever (single + batch, with confirm) work; operator-blind at the DB.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Trash-tab redesign + member-expedited purge + upload/delete reconcile fixes | Status |
|---|---|---|
| contracts / backend | `purgeMedia` op + `MediaStore/Handler/Resource.purgeMedia` | ✅ this CR |
| web | `onFileDone` per-file reconcile; `pendingDelete` tombstone; undo-button fix; Trash-tab MediaGrid + menu + batch + purge | ✅ this CR |
| apple / android | native gallery/trash not built yet | ◻ **deferred (web-first; own CRs)** |

## Definition of done

Uploads reconcile per-file (each tile flips to its real thumbnail the moment it settles); trashing photos in quick
succession no longer re-lists them; the undo button reads on the dark snackbar. The Trash tab renders the same
justified grid as the gallery, with a per-tile checkbox (batch) + a 3-dot menu (Restore / Delete forever) + a batch
toolbar; **Delete forever** permanently purges an already-trashed media now (behind a confirm), and the
security-model + use-case reflect the relaxed reversible/irreversible split. Operator-blind untouched. `./gradlew
check` + `npm run check` + the conformance test + the three trust-chain guards green; the live smoke confirmed.

## Retrospective

The reconcile fixes were pinpointed by a subagent trace before any edit — both were single, well-scoped causes
(no per-file reconcile trigger; a missing optimistic tombstone) rather than deep rewrites. The purge slotted onto
the existing reclaim (generalised `deleteBlobRow`), and the Trash tab dropped onto the shared `MediaGrid`, so the
redesign added overlays, not a new layout.

What didn't go well:

- **I broke the user's dev backend a second time — while *editing* this CR.** cr-0110's retro already recorded
  "never run a parallel `quarkusDev`", but the subtler trap bit here: the user's IntelliJ `quarkusDev` hot-reloads
  my in-progress backend edits (its class-change-agent watches the shared source/`build/`), so it went 500 as I
  typed. The memory note now covers this; the durable fix for a smoke needing new backend code is an **isolated
  worktree** (its own `build/`) or asking the user to restart, never a second dev-mode in the same tree. Recovery
  is a clean restart (the committed source is sound).
- **Re-opened a decision I had just written.** The `Delete forever` action reverses cr-0110's "purge is
  system-only" line. Surfaced it as an explicit choice (with the shared-scope governance cost) before building,
  rather than silently editing the security-model — the right move, but a reminder that a fresh invariant can be
  the very next thing a user wants relaxed.
- **The undo-button regression was mine, one message old.** Overriding only `color` on a class whose base sets a
  light `background` is an easy miss; the lesson is to check the inherited base (`.veyq-text-button` = surface bg)
  before layering a dark-context override.

Process: waited for the explicit "los"; German conversation, English code/docs; Neutral-first DE for the new
strings; per-file reconcile + tombstone are unit/slice-anchored, the redesign UI smoke-verified.
