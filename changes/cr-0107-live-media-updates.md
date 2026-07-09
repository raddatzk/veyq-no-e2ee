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
re-list) **+, only when a second `delete_revision` counter advanced, the current `mediaId` set** for
delete-reconcile (so pure adds/edits stay delta-only, never carrying the O(N) id-set). **Short-poll**,
tab-visibility-gated, only while a Memory is open. **Web vertical only; native is a later CR.**

## Context & constraints

- Durable truth: [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md) (the revision counter +
  conditional GET + keyset delta + `delete_revision`-gated id-set delete-reconcile; short-poll v1; long-poll/push +
  subtree aggregation + tombstone-delta deferred; the rejected legacy O(N) set-hash-per-tick / full-re-list /
  count-only / WebSocket alternatives) +
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

- [ ] 1 contracts — `contracts/api/media.openapi.yaml`: add an **`If-None-Match`** request header (the client's
  `media_revision`) + a **`304`** response to `listNodeMedia`; a **`since`** query param (the client's
  `updatedAt,mediaId` cursor) and a **`sinceDelete`** query param (the client's last-seen `delete_revision`); an
  **`updatedAt`** field + **both revisions** (`media_revision` as the ETag, `delete_revision`) on the response; and,
  on `200`, the **current `mediaId` set** — present **only when `sinceDelete < delete_revision`** (a delete
  occurred) — alongside the (delta) rows. · Done when: `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [ ] 2 backend — migration **`V33`**: `veyq_node.media_revision INT NOT NULL DEFAULT 0` + `veyq_node.delete_revision
  INT NOT NULL DEFAULT 0` + `veyq_media.updated_at TIMESTAMP` (backfill `= created_at`). A
  `MediaStore.bumpMediaRevision(nodeId)` (+ stamp `updated_at`) from **`attach`**, **`setMeta`**, and the delete path;
  the delete path (**`SharingStore.deleteContributedMedia`** + node-delete) **additionally** bumps `delete_revision`
  (**not** `commitCover`/`clearCover`, which bump neither). `listNodeMedia`: read `If-None-Match`, tag from
  `node.media_revision` → **`304`** on match, else a **keyset-delta** (`updated_at > since`, id tie-break) + both
  revisions + the current `mediaId` set **only when `sinceDelete < node.delete_revision`** — both counters read as a
  **consistent pair** (one `veyq_node` row). Slice tests: unchanged → `304` (no rows); an attach bumps
  `media_revision` → `200` delta carries only the new media, **no id-set** (`delete_revision` unchanged); a meta edit
  surfaces via the cursor, **no id-set**; a delete bumps **both** → the `200` **includes** the id-set omitting the
  removed id; a **net-zero** delete+add → id-set present; a cover change bumps **neither**; operator-blind (opaque
  shapes). · Done when: `./gradlew check` green.
- [ ] 3 web — a `mediaRevisionPolling.ts` (mirror `grantDeliveryPolling`: interval + `documentVisible` gate +
  immediate `visibilitychange` tick + start/stop). Wire into `MemoryDetail`: cache **both revisions** + the
  `(updatedAt,mediaId)` cursor + `items`; `load()`/poll sends `If-None-Match` (media_revision) + `sinceDelete`
  (delete_revision); on `304` skip; on `200` **upsert** the delta via `mergeItems` and, **only if the id-set is
  present** (a delete occurred), **drop** cached ids absent from it; advance cursor + both revisions; start the poll
  when a Memory opens, stop on close/navigate. · Done when: `npm run check` green + a **live smoke** (two members on
  one Memory: one uploads → the other's open grid **auto-inserts** the tiles, those polls carrying **no id-set**;
  one deletes their own contribution → the next poll **carries the id-set** and the tile **drops**; an unchanged
  re-open does no media list = `304`).
- [ ] 4 coverage/docs — fill [uc.memory.live-updates.yaml](../docs/coverage/features/memory/use-cases/uc.memory.live-updates.yaml)
  (backend slice + web unit + live smoke + http), **drop `status: planned`**, mark the use-case **built (web)** in
  [memory/_index.md](../docs/features/memory/_index.md); apple/android stay native-deferred gaps. · Done when:
  `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py` green.

## Definition of done

An open Memory gallery reflects other members'/contributors' media **adds + removes live** (auto-insert new tiles,
drop removed), visibility-gated + stopped on close; an **unchanged re-open/poll is a `304`** (no media query,
O(1) counter compare); an **active upload streams keyset deltas** (delta-only, **no id-set** while nothing is
deleted), never a full re-list; **deletes + net-zero delete+add reconcile** via the current `mediaId` set, sent
**only when `delete_revision` advanced**; `media_revision` bumps on attach/meta-edit/delete and `delete_revision`
**only** on delete — a cover change bumps **neither**. `K_mem` untouched, the record + signal stay
**operator-blind**. Guards green (`./gradlew check`,
`npm run check`, the four `docs/` validators, a live smoke). **Out of scope (follow-ups):** long-poll / a push
channel (sub-second latency, shared with #6 live journal); a Memory + chapters **subtree** aggregated live grid; a
curator "delete my own media" endpoint; **native parity**.

## Retrospective

_(to be filled at close)_
