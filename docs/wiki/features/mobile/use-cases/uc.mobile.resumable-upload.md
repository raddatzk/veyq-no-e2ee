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
The client seals the media, splits it into fixed-size **encrypted chunks**, and registers the blob
(`startBlob` → `uploadId` + per-chunk **presigned PUT** URLs). Chunks PUT **directly to object storage**;
the server tracks which chunks have arrived and finalizes on `completeBlob`
(`apple/Sources/VeyqCrypto/Media.swift`, `android/core/.../Media.kt`,
backend `uploads/blob/BlobUploadHandler.kt`).

## Main flow
1. The curator picks media in the app.
2. The client **seals** it under the content key and splits it into encrypted chunks; it computes a
   **dedup token**.
3. `startBlob` declares total chunks + size; the server returns an `uploadId` and **presigned PUT** URLs.
4. The client **PUTs each chunk** to object storage; progress is tracked per chunk.
5. `completeBlob` finalizes the blob; the client attaches it to the Memory as **media** with sealed
   metadata.

## Alternate / exception flows
- A1: The **connection drops** mid-upload → the client re-queries progress and re-PUTs only the
  **missing chunks** (resume, not restart).
- A2: The **same bytes already exist** (dedup token matches) → the upload short-circuits; no re-transfer.
- E1: A **presigned URL expires** before a chunk lands → the client requests a fresh URL and retries that
  chunk.
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
