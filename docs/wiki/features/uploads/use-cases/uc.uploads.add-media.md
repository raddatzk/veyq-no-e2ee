# UC: Add Media To A Memory

- **ID:** uc.uploads.add-media
- **Primary actor:** Memory Curator
- **Goal:** Add encrypted photo or video media to a Memory or Chapter.
- **Preconditions:** The actor is authenticated, holds the target scope key, and the scope has a content key.
- **Postconditions (success):** The media ciphertext and optional thumbnail ciphertext are stored against the target Memory or Chapter and can be listed in gallery order.

## Main flow

1. The client opens the target scope and content key.
2. The client seals the selected media bytes and optional thumbnail under the content key.
3. The client posts the ciphertext, MIME type, plaintext byte size and `takenAt` timestamp to a Memory or Chapter node.
4. The server verifies key-holder access, active tenant membership when applicable, target node type, known suite and content-key existence.
5. The server stores only ciphertext plus low-sensitivity ordering metadata.

## Alternate / exception flows

- E1: The caller is not a key-holder or active tenant member -> reject.
- E2: The node is a Folder or belongs to another scope -> reject.
- E3: The scope has no content key -> reject before storing ciphertext.

## Quality Notes

- The server remains blind to media plaintext and thumbnails.
- This core slice stores a single encrypted blob in the database; resumable BlobStore-backed uploads are covered by `uc.uploads.resumable-upload`.

## Related

- Feature: [Uploads](../_index.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md)
- Related use-cases: `uc.uploads.resumable-upload`, `uc.memory.browse`
