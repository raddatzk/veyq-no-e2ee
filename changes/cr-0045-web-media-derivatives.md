---
id: cr-0045
title: Web media derivatives — thumbnail/preview/poster pipeline (en.memory.derivatives, web slice)
status: done
satisfies: [en.memory.derivatives]
adrs: ["0013", "0012"]
invariants: [operator-blind-e2ee]
---

## Goal

The **web vertical slice** of the media derivatives pipeline ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)),
the enabler ADR 0013 deferred and the rule says comes **before** the gallery. On add-media the client generates
a **thumbnail** (JPEG, longest edge 400 px, q0.80) and a **preview** (JPEG, 2048 px, q0.85) — for video, a JPEG
**poster** from the first frame used for both — seals each as its **own** `K_blob` blob, and uploads all three
(original + 2 derivatives) in **one batch**; the `media` record carries them **role-tagged**. Read-back returns
the tiers so a later gallery can fetch grid=thumbnail / lightbox=preview / download=original without ever
pulling the full original for a grid. Transport is reused **verbatim** from [en.memory.media](../docs/features/memory/enablers/en.memory.media.md)
— a derivative is just another blob.

**In scope:** contracts (role-tag delta) + backend (schema + complete/list) + web (deriver + add-media +
read-back tiers). **Out of scope (own later CRs):** the gallery UI itself ([uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)),
the **apple/android** parity slices, motion-preview, WebP.

## Context & constraints

- **Reuses the media transport, no new crypto.** Each tier is sealed exactly like any blob — chunked AEAD
  under a fresh random `K_blob` wrapped under the Memory's `K_mem` ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)),
  direct-to-store presigned PUT/GET ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)).
  No `chunk_frame`/vector change.
- **Per-blob keys** (enabler decision 1): each tier keeps its **own** `K_blob`, never a shared one — the
  `chunk_frame` AAD only binds `index ‖ total` within a blob, so a shared key would let the blind store splice
  a chunk across derivatives. Per-blob forbids that for free.
- **No backfill** (enabler decision 2): the instance is treated as **fresh** — any current pre-derivative
  media (cr-0028) is wiped, not migrated. Every image/video media has its derivatives from upload.
- **Parity is by property here, not bytes.** Sealing stays vector-gated/byte-identical, but the pixel stage
  (decode → downscale → JPEG re-encode) is **platform-native and byte-divergent by construction** — a
  derivative need only be a valid JPEG at the target longest-edge. Generation is verified by **property**
  (decodable, longest edge ≤ target, dims/quality in range), not a byte vector. This is the one deliberate
  exception to vector-gated parity (enabler *Quality notes*); the crypto layer is unaffected.
- **jsdom cannot decode real Canvas/Image/Video.** The pixel work is hidden behind a small **injectable
  deriver** interface, so the *wiring* (dimension math, role/batch assembly, tier selection on read-back) is
  unit-tested under vitest with a stubbed deriver, while the real Canvas/`<video>` decode is exercised by a
  **live browser round-trip** (the automated web-e2e harness is currently disabled — see cr-0044 — so this is a
  manual live smoke, the same done-gate cr-0030/cr-0034 used for the shells).
- **Verify:** `cd backend && ./gradlew test`; `cd web && npm run check` (typecheck + test + build); the web
  crypto conformance test must stay green.

## Phases

- [x] 1 contracts — role-tag delta on `contracts/api/media.openapi.yaml`. `completeMedia`: one request item now
  groups several uploads with roles — `{ nodeId, originalUploadId, derivatives: [{ kind: THUMBNAIL|PREVIEW,
  uploadId }] }` (still batch-shaped; one item = one logical media with up to 3 blobs). `MediaSummary`: carries
  the original blob-ref **plus** `derivatives: [{ kind, blobId, chunkCount, byteSize, manifest, wrappedBlobKey
  }]` so the grid can fetch the thumbnail tier alone. Regenerate clients (`npm run generate:api`,
  `openApiGenerateSpecs`). · Done when: contracts generate; backend + web compile against the new models.
- [x] 2 backend — `V13__media_derivatives.sql` + complete/list. Schema: keep `veyq_media.blob_id` as the
  original; add `veyq_media_derivative (media_id, kind, blob_id)`. `completeMedia` verifies + completes all of
  an item's uploads (declare-then-verify per blob, byte budget per blob), writes one blob per upload and one
  media with its derivative rows; guards unchanged (grant, MEMORY/CHAPTER target). `listNodeMedia` returns
  original + derivative tiers with their wraps. · Done when: `./gradlew test` green with new slice tests
  (three uploads → one media + two derivatives; list returns tiers with correct wraps; per-blob budget).
- [x] 3 web — deriver + add-media + read-back tiers in `web/src/features/memory/media.ts`. An injectable
  `MediaDeriver`: `deriveImage(blob, { maxEdge, quality }) → Uint8Array` (Canvas), `deriveVideoPoster(blob) →
  Uint8Array` (`<video>` → seek 0 → canvas), plus pure longest-edge target math. `addMedia` generates
  thumbnail + preview (image: two downscales; video: one poster for both), seals each as a `K_blob` blob,
  `startBlobUploads` (3 items) → PUT all → `completeMedia` (one role-tagged media item). Read-back helpers pick
  the thumbnail tier (grid) / preview (lightbox) / original (download) and open it. · Done when: `npm run
  check` green (vitest units for dimension math, role/batch assembly, tier selection with a stubbed deriver;
  conformance test still green) **and** a manual live round-trip — a real image **and** a real video through
  decode→downscale/poster→seal→upload→list→fetch-thumbnail→decode, dimensions/decodability confirmed — with
  1–2 screenshots.
- [x] 4 coverage — track the derivative-generation + read-back tests under `docs/coverage/` for
  `en.memory.derivatives` (mirroring how `en.memory.media` is tracked), apple/android marked as gaps. · Done
  when: `python3 scripts/validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.

## Definition of done

On web, add-media generates + seals a thumbnail and preview (image downscales; video first-frame poster) each
as its own `K_blob` blob, uploads original + both derivatives in one batch, and the `media` record carries them
role-tagged; read-back exposes the three tiers so a grid fetches only the thumbnail. `./gradlew test` and `npm
run check` green; the live image+video round-trip is confirmed with screenshots; coverage + change + doc-link
guards green. No `chunk_frame`/crypto change; operator-blind end to end. apple/android parity and the gallery
UI are explicitly deferred to their own CRs.

## Retrospective

**Outcome.** All four phases landed. Contracts carry the role-tag delta; the backend stores derivatives in
`veyq_media_derivative` with per-blob wraps and an atomic complete (all-or-nothing per media); the web data
layer generates thumbnail + preview (image: two downscales; video: one first-frame poster for both) behind an
injectable deriver and exposes grid/lightbox/download tiers. `./gradlew test` + `npm run check` + the three doc
guards are green. The **live browser round-trip** was driven for real: a 1600×900 PNG → thumbnail **400×225**
(3.9 KB) / preview 1600×900 (24.3 KB) / pristine original (1.3 MB), and a 640×360 webm → a 640×360 first-frame
poster reused for both tiers — every tier sealed under its own `K_blob`, PUT/GET via real presigned URLs against
the bundled Garage, decoded back in-browser. Screenshots captured.

**What went well.** The enabler had already settled the four design points, so the slicing was unambiguous
(per-blob keys, no backfill, video poster, legacy sizes). Reusing the media transport verbatim meant the crypto
layer needed zero change — a derivative really is "just another blob." The injectable `MediaDeriver` kept the
wiring (dimension math, role/batch assembly, tier selection) fully unit-testable under vitest while the real
Canvas/`<video>` decode was proven by the live smoke — the split the CR called for held up cleanly.

**What went wrong / friction (both directions).**
- *Planning (mine):* the CR said phase 4 should "track the tests … mirroring how `en.memory.media` is tracked"
  — but `en.memory.media` has **no** coverage file (the system is use-case-keyed, enablers have none). The
  instruction was unactionable as literally written; I resolved it by surfacing the data-layer tests through the
  consumer use-case `uc.memory.gallery` (the established "logic covered, UI still a gap" shape). Future enabler
  CRs should name the **consuming use-case** as the coverage home, not the enabler.
- *Execution (mine):* I edited backend sources while the user's `quarkusDev` was live, which put it into a
  broken live-reload state; I then had to stop it and relaunch with the store config. A heads-up before touching
  backend code under a running dev server would have avoided the disruption.
- *Environment (ours):* the live smoke was almost blocked because the existing `127.0.0.1` tenant owner's Secret
  Key (emergency kit) is random and printed once by `seed-tenant.mts` — unrecoverable, so `unlock()` couldn't
  run. Worked around by seeding a fresh `media.test` tenant and overriding the active-tenant Host via a
  (env-gated) Vite proxy. The user flagged the real fix: **reproducible dev seed data with keys loadable from a
  file** (follow-up task spawned). Also hit a flush/async race reading the invite link from the backend log
  (the mailer logs after the HTTP response returns) — a one-off accept script sidestepped it.
- *Process (mine):* the same-origin store path (avoid CORS by signing presigned URLs for the web origin +
  proxying `/<bucket>` with the Host preserved for SigV4) was already documented in `infra/README.md`; I
  rederived it rather than reading the infra README first. Reading it up front would have saved a detour.

**Carried forward.** apple/android derivative parity + the gallery UI are their own CRs (deferred by design).
A `/veyq` same-origin store proxy + a `veyq-web-smoke` launch config were added as durable dev affordances
(inert in normal dev). The dev-seed-data improvement is tracked as a follow-up.
