---
id: cr-0058
title: Web media upload — gallery picker + global uploads tray over the ready data layer (web)
status: done
satisfies: [uc.memory.upload]
adrs: ["0013"]
invariants: [operator-blind-e2ee]
---

## Goal

Make media actually uploadable from the web shell. The data layer (`media.ts addMedia` — seal chunks under a
random `K_blob`, presigned direct-to-store PUT, batch start→complete) + backend control plane + derivatives are
already in (cr-0028/0029/0045); today `addMedia` is only called from tests. Port the **pre-E2EE design**
(`legacy/frontend-noe2ee`): a file picker in the open Memory's gallery, a **global upload queue**, and a TopBar
**uploads button + popup** showing every in-flight upload with progress + cancel — wired onto the contracts
`addMedia`, keeping the legacy UI and swapping the data layer (per `web/CLAUDE.md`).

## Context & constraints

- **Port, don't invent.** Source: `legacy/frontend-noe2ee` — `useUploadQueue.ts` (the singleton queue),
  `UploadStatusMenu.vue` (the TopBar button+popup), `useMemoryMediaUpload.ts` + `MemoryUploadDialog.vue` (the
  file-pick + Safari handling). The queue's `uploadFile` becomes our `addMedia`.
- **The data layer gains two hooks.** `addMedia` is atomic (start → `Promise.all` PUTs → complete) with no
  progress/abort. Add an optional **`onProgress(loadedBytes)`** (count completed chunk PUTs) and an
  **`AbortSignal`** (passed to each PUT + the start/complete calls). Also make **derivation best-effort** —
  underivable/empty-type bytes upload **original-only** instead of failing.
- **Safari Photos import (E3).** Accept files by **extension** when `file.type` is empty, **re-read + merge**
  `input.files` over a short retry window (dedup by `name:size:lastModified:type`), and **infer `mediaType`**
  from the extension so derivation can run for HEIC/MOV.
- **Mount points.** The uploads button goes in `AppTopbar`'s empty `workspace-topbar-actions` slot (the design
  source's WorkspaceTopbar actions); the picker lives in the `MemoryDetail` gallery header; the queue is a
  module singleton so uploads survive navigation.
- **Operator-blind unchanged** (ADR 0013): only ciphertext + opaque sizes/counts leave the device.
- **Scope (first slice).** Sequential (one file at a time); images + video (best-effort poster). **Deferred:**
  resumable uploads (ADR 0013 — large-media/video hardening), upload speed/ETA estimation, drag-and-drop,
  server-side session-abort on cancel (rely on store GC), native (apple/android) parity, parallelism.

## Phases

- [x] 1 data-layer hooks (web) — `addMedia` gains `onProgress` + `signal`; derivation becomes best-effort
  (try/catch → original-only). · Done when: `npm run check` green; `media.test.ts` covers progress callbacks,
  abort, and an underivable source → original-only.
- [x] 2 upload queue (web) — port `useUploadQueue` as a module singleton (`uploadQueue.ts`): batches of files
  against `{scopeId, nodeId, memoryKey}`, sequential runner, status (queued/uploading/done/failed/cancelled),
  progress, cancel, retry-on-transient, acknowledge/clear; `uploadFile` = `addMedia` with progress + abort. ·
  Done when: `npm run check` green; a store test round-trips enqueue → upload (mocked `addMedia`) → done, plus
  cancel + transient-retry + the Safari extension/merge/dedup file-selection helper.
- [x] 3 UI (web) — `UploadStatusMenu.vue` (button + popup) mounted in `AppTopbar`; an **Upload** affordance +
  hidden file input in the `MemoryDetail` gallery header that enqueues against the current Memory; on a batch's
  completion, reload that Memory's media. i18n for the tray + status labels. · Done when: `npm run check` green.
- [x] 4 coverage + verify — new
  [uc.memory.upload.yaml](../docs/coverage/features/memory/use-cases/uc.memory.upload.yaml); live browser smoke
  against a seeded instance: pick an image → popup shows progress → done → it appears in the gallery; cancel
  mid-upload; a `type:''`/HEIC pick still enqueues. · Done when: the three trust-chain guards green; the smoke
  confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Media upload (picker + global uploads tray) | Status |
|---|---|---|
| web | gallery picker + queue + TopBar uploads popup over `addMedia` | ◻ this CR |
| apple / android | `VeyqClient.addMedia` data layer exists; the upload UI is their own later CR | ◻ **separate (native)** |

## Definition of done

A user opens a Memory, picks photos/videos, and they upload in the background with a TopBar tray showing
progress + cancel; finished media appears in the gallery. Safari Photos imports (empty-type / late-populated)
work. Operator-blindness is untouched. `npm run check` + the three guards green; the smoke confirmed with the
queue popup. Resumable uploads, speed/ETA, drag-drop, and native parity stay their own work.

## Retrospective

Shipped web media upload in four phases over the ready data layer, ported from `frontend-noe2ee`:
`addMedia` progress/abort/best-effort derivation → a module-singleton `uploadQueue` → the TopBar
`UploadStatusMenu` tray + a gallery picker (with the Safari Photos handling). `npm run check` (186 web tests
incl. media progress/abort/best-effort, the queue runner/cancel/retry/Safari helpers, and the tray) + the three
trust-chain guards green.

**Live smoke (re-seeded instance, driven in the preview): full end-to-end confirmed.** Created + opened a
Memory, injected a PNG into the picker → debounced enqueue → the tray showed the batch + progress → **the
ciphertext landed in Garage** (the `veyq` bucket held 1 object / 98 B) → `completeMedia` attached it → the
gallery reloaded to **"1 Objekt"**.

**Surfaced along the way (good live-smoke value):**
- The first run failed with `store_unavailable` because the dev backend was started with the **in-memory store**
  (`directTransport:false`); the web correctly surfaced that and the tray showed *Failed* + the retry. Re-ran with
  the backend pointed at **Garage** (`veyq.blob-store.public-endpoint = http://127.0.0.1:8091`, so presigned PUTs
  ride the Vite `/veyq` proxy same-origin with the SigV4 Host preserved) → bytes flowed. This was the first
  **browser** exercise of the upload PUT (cr-0028 only had the server-side `BlobBatchE2ETest`); it works through
  the dev proxy.
- The headless picker can't be driven via the OS file dialog, so the smoke injected the File via `DataTransfer`
  (`input.files`), which Chromium allows.

**Deferred (own later work):** resumable uploads (ADR 0013, large-media/video), speed/ETA, drag-and-drop,
server-side session-abort on cancel, parallelism, and native (apple/android) upload UI.
