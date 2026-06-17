# UC: Upload, Store And Attach Media (Unified)

- **ID:** uc.uploads.unified-media
- **Primary actor:** Memory Curator
- **Goal:** Get any media — a small photo or a large video — into a Memory through one upload path, and read it back through one download path.
- **Preconditions:** The actor is authenticated, holds the target scope key, and the scope has a content key.
- **Postconditions (success):** The media's ciphertext frames are stored once per scope (deduplicated), attached to a Memory or Chapter with any client-generated derivatives (thumbnail/preview), and listable in gallery order.

## Concept

Every file becomes a **blob**: a set of AEAD frames (`v1.sealFrame`, AAD binds index + total). A small file is one frame; a large file is many — there is no separate "small" vs "resumable" path. Frames live in the BlobStore (**S3-only** in production — any S3-compatible endpoint, external or a bundled MinIO; `InMemoryBlobStore` for dev/test — see [ADR 0021](../../../../adr/0021-s3-storage-and-presigned-direct-to-store.md)), never the DB; the DB holds only the upload session + chunk manifest. A **media** attaches a stored blob to a Memory/Chapter together with typed **derivatives**, which are themselves ordinary blobs uploaded the same way (no special thumbnail API). The server stays blind: it sees only ciphertext frames, a per-scope dedup token, and low-sensitivity metadata (mime, size).

### Proxied vs presigned direct-to-store

Frames move one of two ways, and the **client** chooses per transfer (the backend only *offers* the direct path when the store can presign; the in-memory dev backend can't, so everything stays proxied there):

- **Proxied** — the frame travels through the backend (`PUT`/`GET .../chunks/{i}`, base64-in-JSON). Always available.
- **Presigned direct-to-store** — the client `PUT`s/`GET`s the **raw sealed frame** straight to S3 via a short-lived signed URL; the backend never touches the bytes. The store only ever sees ciphertext, so this discloses nothing.

**Upload always presigns when S3 is available; download only for multi-frame blobs (`totalChunks > 1`).** The asymmetry is deliberate: the upload's presigned `PUT` URLs ride along in the resume-status response the client fetches anyway → no extra round-trip, so even a one-frame thumbnail goes direct. Download has no such piggyback — fetching presigned `GET` URLs is a dedicated extra request, so for one-frame thumbnails/previews (the hot, highly-parallel gallery path) the client stays proxied and only takes the direct path for large multi-frame originals, where the one extra round-trip pays for itself in offloaded bandwidth.

## Main flow

1. The client splits the plaintext into fixed-size chunks (one chunk for a small file) and computes a per-scope dedup token over the whole plaintext.
2. The client calls `startBlob`. If the scope already holds that content → `READY` with the existing `blobId` (the bytes are never re-sent, even for a huge file). Otherwise → `UPLOADING` with an `uploadId`.
3. The client seals each chunk into a frame and PUTs it — directly to S3 via a presigned URL when offered (those URLs come back in the same resume-status response), otherwise through the proxied chunk endpoint. PUT is idempotent: a retried/resumed upload asks which frames landed and re-sends only the missing ones.
4. The client finalizes (`complete`); the server verifies every frame is present, dedups once more at finalize, and promotes the frames to a content blob.
5. The client attaches the blob (and any derivative blobs) to a Memory/Chapter as a media.
6. Download pulls each frame and opens it (the AAD checks position), concatenating the plaintext. Large multi-frame blobs fetch the frames directly from S3 via presigned `GET` URLs; one-frame thumbnails/previews stay on the proxied path (see *Proxied vs presigned* above).

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
