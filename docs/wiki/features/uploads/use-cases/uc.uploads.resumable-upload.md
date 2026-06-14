# UC: Resume An Interrupted Upload

- **ID:** uc.uploads.resumable-upload
- **Primary actor:** Memory Curator
- **Goal:** Upload large encrypted media in resumable chunks.
- **Preconditions:** The actor is authenticated, holds the target scope key, the target node is a Memory or Chapter, and the scope has a content key.
- **Postconditions (success):** Every encrypted frame is present in the BlobStore manifest and the upload session is finalized.

## Main flow

1. The client splits the media into fixed-size plaintext chunks.
2. The client opens a resumable upload session with bounded `totalChunks` and plaintext `byteSize`.
3. The client seals each chunk as an independent AEAD frame whose AAD binds `index` and `total`.
4. The client uploads frames by index. Re-sending an existing chunk is idempotent.
5. After an interruption, the client asks for progress and uploads only missing chunks.
6. The client finalizes once every chunk index has landed.
7. The client downloads frames and opens them locally to reconstruct the media.

## Alternate / exception flows

- E1: The caller is not a key-holder or active tenant member -> reject.
- E2: The node is a Folder or belongs to another scope -> reject.
- E3: `totalChunks`, declared size, decoded frame size or cumulative stored frame data exceeds configured limits -> reject before expensive memory or BlobStore work.
- E4: Completion is requested before every chunk is present -> reject without materializing an attacker-sized expected index set.
- E5: Upload is already completed -> further chunk writes are rejected; completion remains idempotent.

## Quality Notes

- The server stores only encrypted frames and upload metadata.
- The default server limits are configurable through `veyq.uploads.max-chunks`, `veyq.uploads.max-upload-bytes` and `veyq.uploads.max-frame-bytes`.
- Direct-to-object-store uploads and streaming/transcode derivatives are later slices.

## Related

- Feature: [Uploads](../_index.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md)
- Related use-cases: `uc.uploads.add-media`, `uc.memory.browse`
