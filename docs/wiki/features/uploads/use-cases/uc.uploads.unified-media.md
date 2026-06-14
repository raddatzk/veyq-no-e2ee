# UC: Upload, Store And Attach Media (Unified)

- **ID:** uc.uploads.unified-media
- **Primary actor:** Memory Curator
- **Goal:** Get any media — a small photo or a large video — into a Memory through one upload path, and read it back through one download path.
- **Preconditions:** The actor is authenticated, holds the target scope key, and the scope has a content key.
- **Postconditions (success):** The media's ciphertext frames are stored once per scope (deduplicated), attached to a Memory or Chapter with any client-generated derivatives (thumbnail/preview), and listable in gallery order.

## Concept

Every file becomes a **blob**: a set of AEAD frames (`v1.sealFrame`, AAD binds index + total). A small file is one frame; a large file is many — there is no separate "small" vs "resumable" path. Frames live in the BlobStore (filesystem now; S3/MinIO or per-tenant buckets drop in behind the interface), never the DB; the DB holds only the upload session + chunk manifest. A **media** attaches a stored blob to a Memory/Chapter together with typed **derivatives**, which are themselves ordinary blobs uploaded the same way (no special thumbnail API). The server stays blind: it sees only ciphertext frames, a per-scope dedup token, and low-sensitivity metadata (mime, size, `takenAt`).

## Main flow

1. The client splits the plaintext into fixed-size chunks (one chunk for a small file) and computes a per-scope dedup token over the whole plaintext.
2. The client calls `startBlob`. If the scope already holds that content → `READY` with the existing `blobId` (the bytes are never re-sent, even for a huge file). Otherwise → `UPLOADING` with an `uploadId`.
3. The client seals each chunk into a frame and PUTs it. PUT is idempotent: a retried/resumed upload asks which frames landed and re-sends only the missing ones.
4. The client finalizes (`complete`); the server verifies every frame is present, dedups once more at finalize, and promotes the frames to a content blob.
5. The client attaches the blob (and any derivative blobs) to a Memory/Chapter as a media.
6. Download pulls each frame and opens it (the AAD checks position), concatenating the plaintext — the same path for a one-frame photo or a many-frame video.

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
