# UC: Write the journal

- **ID:** uc.journal.write
- **Primary actor:** Memory Curator
- **Goal:** Keep a free-form Markdown journal on a Memory or chapter, autosaved, that readers see as
  formatted text — without the server ever reading it.
- **Preconditions:** Authenticated; may edit the node; holds the space content key; the node is a
  Memory or chapter (not a folder).
- **Postconditions (success):** The journal body is sealed under the content key and persisted; a
  Markdown projection is stored for readers; the operator holds only ciphertext, sequence numbers and
  timestamps.

## Solo vs. live
This is the **solo** REST-autosave path; concurrent multi-user editing is
[live co-edit](uc.journal.live-coedit.md). Both write to the **same sealed store** (snapshot + sealed
update log + a Markdown projection), so a journal can move between solo and live without conversion.

## Main flow
1. The curator opens the journal editor on a Memory/chapter and writes **Markdown** (headings, lists,
   emphasis, links).
2. The client **autosaves**: it loads the current state (`GET …/journal/live` → snapshot + updates),
   applies the edit, and writes a compacted snapshot (`PUT …/journal/snapshot`) carrying (a) the sealed
   CRDT snapshot, (b) the sealed **Markdown projection** and (c) the `coveredSeq` it folded in.
3. The server prunes superseded updates `≤ coveredSeq` and stores the new sealed bytes; it **never
   decrypts**.
4. A reader loads `GET …/journal` → the sealed Markdown projection → the client decrypts and renders it
   safely.

## Alternate / exception flows
- A1: A small incremental edit appends a **single sealed update** (`POST …/journal/updates`) instead of
  a full snapshot; compaction happens later.
- E1: The node is a **folder** → rejected (`NOT_A_MEMORY`); journals exist only on Memories/chapters.
- E2: An **unknown crypto suite** on the sealed payload → rejected (`UNKNOWN_SUITE`).
- E3: `coveredSeq` exceeds the server's next sequence → rejected (`BAD_SEQ`); the client must fold in all
  updates it claims to cover.
- E4: The sealed body exceeds the **size cap** (1 MiB default) → rejected.
- E5: The content key is unavailable on this device → the curator is prompted to enroll the device or
  use the recovery code before editing.

## Quality notes
- *Confidentiality (paramount):* the full Markdown source (including embed tokens) is **encrypted**; the
  operator sees only ciphertext, a monotonic seq, the author id and timestamps.
- *Safety (rendering):* the reader renders through an **escape-first** Markdown path — HTML is escaped,
  only `http(s)` links are clickable, `javascript:` URLs degrade to literal text (embed safety in
  [embed](uc.journal.embed.md)).
- *Integrity:* per-message **sub-keys** (HKDF over a fresh salt) avoid GCM nonce reuse under the
  long-lived content key.

## Related
- Feature: [Journal](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (content keys, per-message sub-key),
  [Live Collaboration](../../../crosscutting/live-collaboration.md) (snapshot + increments)
- Decisions: ADR 0016 (collaborative journal live sync)
- Related use-cases: `uc.journal.embed`, `uc.journal.live-coedit`, `uc.memory.create-tree`
