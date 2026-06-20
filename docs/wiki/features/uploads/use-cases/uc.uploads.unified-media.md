# UC: Upload, Store And Attach Media (Unified)

- **ID:** uc.uploads.unified-media
- **Primary actor:** Memory Curator
- **Goal:** Get any media — a small photo or a large video — into a Memory through one upload path, and read it back through one download path.
- **Preconditions:** The actor is authenticated, holds the target scope key, and the scope has a content key.
- **Postconditions (success):** The media's ciphertext frames are stored once per scope (deduplicated), attached to a Memory or Chapter with any client-generated derivatives (thumbnail/preview), and listable in gallery order.

## Concept

Every file becomes a **blob**: a set of AEAD frames (`v1.sealFrame`, AAD binds index + total). A small file is one frame; a large file is many — there is no separate "small" vs "resumable" path. Frames live in the BlobStore (**S3-only** in production — any S3-compatible endpoint, external or a bundled MinIO; `InMemoryBlobStore` is dev/test-only and **cannot presign**, so it has no media-transport path — see [ADR 0021](../../../../adr/0021-s3-storage-and-presigned-direct-to-store.md)), never the DB; the DB holds only the upload session + chunk manifest. A **media** attaches a stored blob to a Memory/Chapter together with typed **derivatives**, which are themselves ordinary blobs uploaded the same way (no special thumbnail API). The server stays blind: it sees only ciphertext frames, a per-scope dedup token, and low-sensitivity metadata (mime, size).

### Direct-to-store, batched transport

All frames move **directly between client and object store** via short-lived presigned URLs ([ADR 0021](../../../../adr/0021-s3-storage-and-presigned-direct-to-store.md)); the backend only ever handles the control plane (start / finalize / presign), never the bytes. The store holds AEAD ciphertext exclusively, so a presigned URL discloses nothing. There is **no proxied fallback** — the store must be presign-capable and **client-reachable** (an external S3, or the bundled MinIO behind a reverse-proxy path route); a misconfigured store is caught by a startup preflight (store reachable + CORS probe) and advertised via `/api/setup` (`directTransport`).

A photo is three blobs (original + thumbnail + preview), so to avoid a round-trip per blob the control plane is **batched**:

- **`blob-uploads-batch`** — dedup/open many blobs in one call; each result is `READY` (dedup hit) or `UPLOADING` with presigned `PUT` URLs for the missing chunks. Pass an `uploadId` to **resume** (the server stats the store and re-presigns only what's still missing).
- **media `…-batch`** (finalize) — complete many items' uploads and attach them as media in one call, per-item best-effort.
- **`blob-downloads-batch`** — presign `GET` URLs for a whole gallery page in one call.

The public-share guest path mirrors these three (proof-gated; downloads gated to the share's subtree). CORS only matters for the **web** client (native apps aren't subject to it) and is configured on the store; the bundled MinIO behind a same-origin Traefik path route needs none.

## Main flow

1. The client splits each blob's plaintext into fixed-size chunks (one for a small file) and computes a per-scope dedup token over the whole plaintext.
2. **Batch-start** for all of a photo's blobs (original + derivatives): each result is `READY` (the scope already holds that content — bytes never re-sent, even for a huge file) or `UPLOADING` with presigned `PUT` URLs for the missing chunks.
3. The client seals each missing chunk and **PUTs it directly to the store**, bound-parallel across all blobs. A retried/resumed upload re-calls batch-start with the `uploadId` → the server counts the chunks already in the store and re-presigns only the missing ones.
4. **Batch-finalize**: the server verifies every frame is present, dedups once more, promotes the frames to content blobs, and attaches each (with its derivatives) as media — per item, so one failed item doesn't sink the batch.
5. Gallery download: **batch-presign-get** for the page's blobs, then fetch every frame directly from the store (bound-parallel) and open it (the AAD checks position), concatenating the plaintext.

## Alternate / exception flows

- E1: The caller is not a key-holder or active tenant member -> reject.
- E2: The target node is a Folder or belongs to another scope -> reject (media live only in a Memory/Chapter).
- E3: The scope has no content key -> reject before any upload.
- E4: `complete` is called before every frame has landed -> `INCOMPLETE_UPLOAD`.
- E5: An oversized chunk count, declared size, single frame, or cumulative frame budget -> reject before heavy work.
- E6: Too many concurrent OPEN sessions in a scope -> `TOO_MANY_OPEN_UPLOADS`.
- E7: A blob referenced by a media attach does not exist in the scope -> `BLOB_NOT_FOUND`.

## Quality Notes

- The server remains blind to media plaintext, thumbnails, and previews.
- Dedup is a per-scope keyed token (no cross-scope confirmation-of-file oracle).
- Abandoned OPEN sessions are reclaimed by a scheduled sweep (frames + manifest + session); COMPLETED blobs survive.
- A frame fails to open at the wrong index — the AAD binds position (anti reorder/truncation).

## Related

- Feature: [Uploads](../_index.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md)
- Related use-cases: `uc.memory.browse`, `uc.memory.create-tree`
