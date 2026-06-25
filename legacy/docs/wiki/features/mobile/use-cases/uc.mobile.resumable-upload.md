# UC: Resumable upload from mobile

- **ID:** uc.mobile.resumable-upload
- **Primary actor:** Memory Curator
- **Goal:** Upload photos and videos from a phone reliably over flaky networks — **sealed, chunked and
  resumable** — so a dropped connection never restarts the whole file.
- **Preconditions:** Authenticated; holds the content key for the target Memory; uploading is permitted
  in the space.
- **Postconditions (success):** The media is sealed and stored as a **blob assembled from encrypted
  chunks** uploaded directly to object storage; an interrupted upload **resumes from the missing
  chunks**; the assembled media attaches to the Memory.

## Mechanics
The client seals the media, splits it into fixed-size **encrypted chunks**, and opens a photo's blobs
(original + thumbnail/preview) in ONE **batched** call (`blob-uploads-batch` → per blob: `READY` on a dedup
hit, or an `uploadId` + per-chunk **presigned PUT** URLs). Chunks PUT **directly to object storage**; the
server tracks which arrived (by stat-ing the store) and a single batched finalize (media `…-batch`) completes
the uploads + attaches the media (`apple/Sources/VeyqCrypto/Media.swift`, `android/core/.../Media.kt`,
backend `uploads/blob/BlobUploadHandler.kt` + `uploads/media/MediaHandler.kt`). Transport is direct-to-store
only — there is no proxied path (ADR 0021).

## Main flow
1. The curator picks media in the app.
2. The client **seals** it under the content key and splits it into encrypted chunks; it computes a
   **dedup token**.
3. **Batch-start** declares all of a photo's blobs (chunk counts + sizes); the server returns, per blob,
   `READY` (dedup hit — never re-sent) or an `uploadId` + **presigned PUT** URLs for the missing chunks.
4. The client **PUTs each missing chunk** directly to object storage, parallel across blobs; progress per chunk.
5. **Batch-finalize** completes the uploads and attaches them to the Memory as **media** (with derivatives)
   under sealed metadata.

## Alternate / exception flows
- A1: The **connection drops** mid-upload → the client re-calls **batch-start with the `uploadId`**; the
  server stats the store and re-presigns only the **missing chunks** (resume, not restart).
- A2: The **same bytes already exist** (dedup token matches) → batch-start returns `READY`; no re-transfer.
- E1: A **presigned URL expires** before a chunk lands → the client re-requests it via a fresh batch-start
  and retries that chunk.
- E2: A chunk fails an **integrity / size** check → rejected; the client retries that chunk.
- E3: A **disallowed type** or limits exceeded → rejected with a clear, rate-limited error.

## Quality notes
- *Confidentiality (paramount):* chunks are sealed **client-side**; object storage and the operator hold
  only ciphertext.
- *Resilience:* chunking + resume make large mobile uploads survive backgrounding and network churn.
- *Efficiency:* **direct-to-storage** presigned PUTs keep large transfers off the app server.

## Related
- Feature: [Mobile](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md),
  [Security Model](../../../crosscutting/security-model.md) (content keys)
- Decisions: [AD-03](../../../decisions/README.md) (per-subtree keys)
- Related use-cases: `uc.uploads.unified-media`, `uc.sharing.collect-uploads`
