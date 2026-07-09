---
id: cr-0107
title: Live media updates — media-revision counter + conditional-GET delta, short-polled (web vertical)
status: planned
satisfies: [uc.memory.live-updates]
adrs: ["0033", "0013", "0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Make an open Memory gallery **reflect other members'/contributors' media adds + removes live**, and make an
unchanged re-open **free** ([uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md),
[ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)). A per-node **`media_revision`**
counter is the cheap signal → **conditional GET** on `listNodeMedia` (`304` when unchanged); on change a **keyset
delta** (media past the client's `(updatedAt, id)` cursor — so an active upload streams new items, never a full
re-list) **+ the current `mediaId` set** for delete-reconcile. **Short-poll**, tab-visibility-gated, only while a
Memory is open. **Web vertical only; native is a later CR.**

## Context & constraints

- Durable truth: [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md) (the revision counter +
  conditional GET + keyset delta + id-set delete-reconcile; short-poll v1; long-poll/push + subtree aggregation
  deferred; the rejected legacy O(N) set-hash-per-tick / full-re-list / count-only / WebSocket alternatives) +
  [uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md) (the behaviour + E-flows).
- Reused substrate (not rebuilt): `listNodeMedia` (`GET /api/scopes/{scopeId}/nodes/{nodeId}/media`) + its
  resource/handler/store; the media mutation paths that will bump the counter — `MediaStore.attach` (curator +
  contributor/uploader upload), `MediaStore.setMeta` (cr-0068 edit), `SharingStore.deleteContributedMedia`
  (cr-0102 delete-own); the web gallery `MemoryDetail` + its `mergeItems` reconcile + the optimistic-tile grid
  (cr-0074); the poll pattern of `grantDeliveryPolling` / `scopeRotationPolling` (interval + visibility gate).
- Invariant at risk: **operator-blind-e2ee.** Unchanged — the counter is an opaque number, the delta + id-set are
  opaque `mediaId`s + coarse timestamps; no content, no key crosses. `K_mem`/`K_share` stay on-device.
- **Gallery is own-node** (chapters open to their own galleries) → a **per-node** revision suffices; a Memory +
  chapters **subtree** rollup is out of scope. **Cover changes are excluded** from the counter (they write no
  `veyq_media` row, never list). No authenticated curator delete endpoint exists — the delete-reconcile covers the
  share delete-own + node-delete that do.

## Phases (web vertical; native is a later CR)

- [ ] 1 contracts — `contracts/api/media.openapi.yaml`: add an **`If-None-Match`** request header + a **`304`**
  response to `listNodeMedia`; a **`since`** query param (the client's `updatedAt,mediaId` cursor); an **`updatedAt`**
  field + a revision **ETag** to the response; and, on `200`, the **current `mediaId` set** (for delete-reconcile)
  alongside the (delta) rows. · Done when: `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [ ] 2 backend — migration **`V33`**: `veyq_node.media_revision INT NOT NULL DEFAULT 0` + `veyq_media.updated_at
  TIMESTAMP` (backfill `= created_at`). A `MediaStore.bumpMediaRevision(nodeId)` (+ stamp `updated_at`) called from
  **`attach`**, **`setMeta`**, and **`SharingStore.deleteContributedMedia`** (**not** `commitCover`/`clearCover`).
  `listNodeMedia`: read `If-None-Match`, compute the tag from `node.media_revision` → **`304`** on match, else a
  **keyset-delta** (`updated_at > since`, id tie-break) + the current `mediaId` set + the new ETag. Slice tests:
  unchanged → `304` (no rows); an attach bumps the revision → `200` delta carries only the new media; a meta edit
  surfaces via the cursor; a delete → the id-set omits it; a cover change does **not** bump; operator-blind (opaque
  shapes). · Done when: `./gradlew check` green.
- [ ] 3 web — a `mediaRevisionPolling.ts` (mirror `grantDeliveryPolling`: interval + `documentVisible` gate +
  immediate `visibilitychange` tick + start/stop). Wire into `MemoryDetail`: cache the revision (ETag) + the
  `(updatedAt,mediaId)` cursor + `items`; `load()`/poll sends `If-None-Match`; on `304` skip; on `200` **upsert** the
  delta via `mergeItems` + **drop** cached ids absent from the returned id-set + advance cursor/revision; start the
  poll when a Memory opens, stop on close/navigate. · Done when: `npm run check` green + a **live smoke** (two
  members on one Memory: one uploads → the other's open grid **auto-inserts** the tiles; one deletes their own
  contribution → it **drops** from the other's grid; an unchanged re-open does no media list = `304`).
- [ ] 4 coverage/docs — fill [uc.memory.live-updates.yaml](../docs/coverage/features/memory/use-cases/uc.memory.live-updates.yaml)
  (backend slice + web unit + live smoke + http), **drop `status: planned`**, mark the use-case **built (web)** in
  [memory/_index.md](../docs/features/memory/_index.md); apple/android stay native-deferred gaps. · Done when:
  `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py` green.

## Definition of done

An open Memory gallery reflects other members'/contributors' media **adds + removes live** (auto-insert new tiles,
drop removed), visibility-gated + stopped on close; an **unchanged re-open/poll is a `304`** (no media query,
O(1) counter compare); an **active upload streams keyset deltas**, never a full re-list; **deletes + net-zero
delete+add reconcile** via the current `mediaId` set; `media_revision` bumps on attach/meta-edit/delete but **not**
on a cover change. `K_mem` untouched, the record + signal stay **operator-blind**. Guards green (`./gradlew check`,
`npm run check`, the four `docs/` validators, a live smoke). **Out of scope (follow-ups):** long-poll / a push
channel (sub-second latency, shared with #6 live journal); a Memory + chapters **subtree** aggregated live grid; a
curator "delete my own media" endpoint; **native parity**.

## Retrospective

_(to be filled at close)_
