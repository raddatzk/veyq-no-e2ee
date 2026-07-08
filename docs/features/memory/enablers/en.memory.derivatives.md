# Enabler: Media derivatives — thumbnail / preview / original

- **ID:** en.memory.derivatives
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **refined** — ready to cut a CR. All four design points are settled (role-tagged blobs with
  **per-blob keys**, **no backfill**, **first-frame video poster**, **legacy sizes**); see *Decisions*.
- **Provides:** a **three-tier** view of each media item — a small **thumbnail** (the grid), a medium
  **preview** (the lightbox), and the **original** (download only) — each its own encrypted blob, generated
  **on the client** so the operator never sees a derivable pixel. Lets the gallery render fast and
  bandwidth-light while keeping the pristine original for download.
- **Serves (use-cases):** [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (grid = thumbnail, lightbox =
  preview, download = original); the **add-media** upload flow is where the derivatives are generated.
- **Depends on:** [en.memory.media](en.memory.media.md) — a derivative is **just another blob**, sealed by the
  exact same `K_blob`-under-`K_mem` chunked-AEAD transport; through it [en.memory.tree](en.memory.tree.md) +
  `uc.account.unlock` (identity → `K_SC` → `K_mem`).
- **Preconditions:** an **unlocked** vault; an **open Memory** whose `K_mem` is unwrapped; a configured object
  store — a **hard instance requirement** ([ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md),
  so a running instance always has one).
- **Postconditions (success):** for a media item, up to **three role-tagged blobs** (thumbnail, preview,
  original) exist in the store; the server holds only ciphertext chunks + the role tags + the `K_blob` wraps.
  No plaintext pixel, no content key.

## The three tiers

| role | used by | size / format | fetched when |
|------|---------|---------------------------------------------|--------------|
| **thumbnail** | gallery **grid** | JPEG, longest edge **512 px**, quality **0.80** | listing a Memory |
| **preview** | **lightbox** | JPEG, longest edge **2048 px**, quality **0.85** | opening an item |
| **original** | **download** | **pristine**, as uploaded (full res + metadata) | explicit download only |

- **Sizes are pinned from the legacy E2EE client** (`legacy/apple/App/Veyq/Gallery/MemoryDetailView.swift:318-319`
  — `thumbnail = downscale(maxDim 400, q 0.8)`, `preview = downscale(maxDim 2048, q 0.85)`), so the look
  matches the original product. Tunable, but these are the defaults. (WebP would be smaller — an optional swap,
  not the default.) **Exception (cr-0070):** the **thumbnail** cap was raised **400 → 512 px** so a ~200 px grid
  tile on a 2×/3× display isn't upscaled into mush; preview stays legacy-pinned.
- **Use a high-quality downscale filter.** The thumbnail is a large single-step reduction (e.g. 4032 → 512 ≈ 8×);
  the platform's *cheap* default resampler (web canvas `imageSmoothingQuality: 'low'`) aliases fine detail into
  a smear. Each surface must select its high-quality scaler (web: **progressive-halving** canvas downscale — ≤2×
  steps at `imageSmoothingQuality='high'`, since a single big `drawImage` is one bilinear pass and stays soft;
  apple: ImageIO `CGImageSourceCreateThumbnailAtIndex` — a resampling downscaler; android:
  `createScaledBitmap(filter = true)` after a power-of-two `inSampleSize` pre-reduction). The legacy web client
  sidestepped this entirely by deriving **server-side** (libvips) — not an option here (operator-blind).
- **Video:** decode the **first frame** to a JPEG **poster**, used for **both** the thumbnail and the preview
  (a still — no motion preview this pass); the original video is fetched only on download. Mirrors the legacy
  single-JPEG-poster (`legacy/infra/.env.template:210`).
- Each tier is sealed **exactly like any media blob** — chunked AEAD under its **own fresh random `K_blob`**
  wrapped under the Memory's `K_mem` ([en.memory.media](en.memory.media.md)). **No new crypto primitive** — a
  derivative is another blob with a role tag.
- **Operator-blind generation.** Thumbnail + preview are produced **on the client** (decode → downscale →
  re-encode → seal), so the operator never sees a derivable pixel. Re-encoding strips EXIF from the
  derivatives; the **original** is kept **byte-pristine** (full resolution + metadata) and leaves the device
  only on explicit download.
- **Why the lightbox shows the preview, not the original:** the lightbox is a *viewing* surface, not a
  download — the preview keeps it fast and cheap; the multi-MB original is fetched only when the user actually
  downloads.

## Generation & attachment (in the upload flow)

On upload (the add-media flow), the client derives **preview + thumbnail** from the source (image: downscale;
video: first-frame poster), seals **each as its own blob** under a fresh random `K_blob`, and uploads all
three in **one batch** (the control plane is already batch-shaped, [en.memory.media](en.memory.media.md)). The
**media** record then references the blobs **by role** — `original` plus `derivatives: [{ kind, blob }]`,
`kind ∈ THUMBNAIL | PREVIEW` — exactly the legacy E2EE model
(`legacy/apple/App/Veyq/Home/HomeStore.swift:373`: `derivatives.first { $0.kind == "THUMBNAIL" }?.blob ?? original`).
A type that is neither image nor video (no derivable frame) attaches the **original only**; the gallery falls
back to an icon + name. **Upload always generates the derivatives** — there is no produce-later path.

## Decisions (settled)

1. **Attachment / keying.** The `media` record carries `original` + `derivatives: [{ kind, blob }]` —
   **role-tagged, opaque blob ids**, a contract delta on `media.openapi.yaml` (start/complete/list). Each blob
   keeps **its own `K_blob`** (wrapped under `K_mem`) — **not** a key shared across the three. *Why per-blob:*
   the `chunk_frame` AAD binds only `index ‖ total` (position **within** a blob); a shared key would give
   thumbnail-chunk-`i` and preview-chunk-`i` the same `(key, AAD)`, letting the blind store **splice a chunk
   across derivatives undetectably**. Per-blob keys forbid that for free — no `chunk_frame`/vector change. The
   `<original-name>-<thumbnail|preview>.<suffix>` naming is a **client-side download-filename** convention only
   (derived from the decrypted original name); **storage stays opaque blob ids** — filenames are operator-blind
   under `K_mem`.
2. **No backfill.** The instance is treated as **fresh** — any current pre-derivative media (cr-0028) is
   simply wiped, not migrated. Every image/video media therefore has its derivatives from upload; the gallery
   may **assume** they exist (the only fallback is the rare non-image/non-video type).
3. **Video = first-frame poster** (JPEG), used for both thumbnail and preview; the video original only on
   download. Motion/short-clip preview is a later pass.
4. **Tier sizes:** thumbnail JPEG longest-edge 512 / q0.80 (raised from the legacy 400 for hi-DPI, cr-0070),
   preview JPEG longest-edge 2048 / q0.85 — legacy-pinned (see *The three tiers*).

## Quality notes

- **Operator-blind end to end.** Derivatives are generated and sealed on the client; the store/server hold
  only ciphertext + role tags + wraps. A smaller derivative leaks no more than the original already does
  (coarse size).
- **Parity is by property, not bytes, for the pixel stage.** The *sealing* of every derivative stays
  vector-gated and **byte-identical** across surfaces (`chunk_frame`, [en.memory.media](en.memory.media.md)).
  The *pixel* stage before it — decode → downscale → JPEG re-encode — is **platform-native** (web Canvas,
  apple ImageIO, android Bitmap) and **diverges at the byte level by construction**: a derivative need only be
  a valid JPEG that hits the target longest-edge. Cross-surface parity for derivative *generation* is therefore
  verified by **property** (decodable, longest edge ≤ target, dimensions/quality in range), **not** by a byte
  vector. (This is the one deliberate exception to vector-gated parity, and it is confined to the lossy
  re-encode — the crypto layer is unaffected.)
- **Reuses the media transport verbatim.** No new endpoint *shape* beyond the role tagging — same
  start → direct PUT → complete+attach → direct GET → open ([en.memory.media](en.memory.media.md)),
  batch-shaped (now three blobs per item).
- **The original stays pristine.** Only the original is byte-identical to what the user uploaded (full
  resolution + metadata); it is fetched only on explicit download.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — its end-user
> value surfaces through [uc.memory.gallery](../use-cases/uc.memory.gallery.md).

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.media](en.memory.media.md) (the blob transport this reuses),
  [en.memory.tree](en.memory.tree.md) (the Memory + `K_mem`)
- Use-cases: [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (consumes the three tiers)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (deferred
  thumbnails/previews — **this enabler is where they land**),
  [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_blob` under `K_mem`)
