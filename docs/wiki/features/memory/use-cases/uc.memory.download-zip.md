# UC: Download a Memory as a ZIP

- **ID:** uc.memory.download-zip
- **Primary actor:** Memory Curator
- **Goal:** Download a Memory's original media as a ZIP, optionally including descendant memories.
- **Preconditions:** Authenticated; may read the Memory and **holds the subtree's content key** (so every
  original under it decrypts); a client able to stream to disk.
- **Postconditions (success):** A ZIP of the original media — mirroring the curation tree (folders,
  Memories, chapters) as directories, with collision-free filenames — is streamed to the user's device.

## Main flow
1. The curator selects a Memory and whether to **include descendants**.
2. The client **streams** the export: for each original it fetches the encrypted blob, **decrypts** it
   and appends it to a ZIP written **directly to disk** — never holding the whole archive in memory
   (web: File System Access API / StreamSaver; native: a file). This scales to large exports.
3. The ZIP mirrors the curation tree: folders, Memories and **chapters** become directories named from
   their **decrypted** titles. A Memory's own media go into its directory; each **chapter** is a
   **separate subdirectory**. Buckets / sub-buckets are **flattened** into the Memory directory —
   their attribution is preserved in the filename (next step).
4. To stay collision-free, each original is **renamed** `<takenAt> - <uploaderName>` — plus the
   **bucket name** when it sits in a named (sub-)bucket — using the media's **effective** `takenAt`
   (including any correction; upload time when unknown) and the uploader (its bucket owner). Residual
   clashes (same instant and uploader — e.g. burst shots) get a deterministic ` (2)`, ` (3)`, … suffix
   ordered by media id, so names are unique **and** stable across re-exports.

## Alternate / exception flows
- A1: The curator **cancels** mid-export → the partial file is discarded.
- E1: A **network/IO error** mid-stream → the client retries; if it cannot finish, it fails clearly
  rather than leaving a silently-truncated ZIP.
- E2: The Memory is reached through a **share** whose role forbids bulk download → blocked by the
  share's download permission (a Sharing concern).

## Quality notes
- *E2E:* the **server cannot build the ZIP** (it holds only ciphertext) → the export is **client-side**.
  The produced ZIP is **plaintext on the user's device**, including readable folder names — intended; it
  is the export the user asked for.
- *No partial-decrypt case:* by precondition the client holds the subtree's content key, so **every
  original decrypts** — there is no "skipped/undecryptable item" path. The only future scenario where an
  original might be unexportable is an **inbound foreign share** rendered against its origin (out of
  scope).
- *Originals only:* the export contains the **originals as uploaded**, not transcodes/derivatives.
  Metadata-only corrections (e.g. `takenAt`) stay in metadata and do not alter the exported blob; a
  GPS-strip is applied **pre-upload**, so the stored original is already clean.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (client-side decrypt, holds-the-key)
- Decisions: [AD-02](../../../decisions/README.md) (E2E / blind server), [AD-03](../../../decisions/README.md) (per-subtree keys)
- Related use-cases: `uc.uploads.add-media`, `uc.memory.create-tree`
