# UC: Contribute to a shared Memory

- **ID:** uc.sharing.collaborate
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Contributor
- **Goal:** Share a Memory so external guests can **add** content (upload media into the gallery), beyond
  read-only, still without an account and without the operator reading anything.
- **Preconditions:** The curator owns the Memory and holds its content key; sharing is permitted; the
  recipient gets a **CONTRIBUTOR** link.
- **Postconditions (success):** A CONTRIBUTOR share exists; guests can read the subtree and add
  **encrypted** media attributed to their share; guests **cannot delete**; the operator stays blind.

## Access levels
- **VIEWER** — read only ([share-public-viewer](uc.sharing.share-public-viewer.md)).
- **CONTRIBUTOR** — read + upload blobs + attach them as media; **no deletes**.
- **MANAGER** — *planned, not yet built:* expanded write (set covers, highlight any media); reserved for
  the [Public Manager](../../../actors.md) actor.
- **UPLOADER** — the gallery-blind upload-only link, covered by
  [collect-uploads](uc.sharing.collect-uploads.md).

## Main flow
1. The curator creates a share with role **CONTRIBUTOR** and distributes the sealed link.
2. A Public Contributor opens it, resolves the share and **unwraps** the content key from the fragment
   (as a viewer).
3. The contributor reads the subtree, then **starts a blob upload** (`POST …/blobs`), uploads encrypted
   chunks, and completes it.
4. The contributor **attaches** the finished blob as media on a subtree node
   (`POST …/contributed-media`) with sealed metadata; the server verifies the blob was uploaded **via
   this share**.
5. The new media appears in the gallery for everyone on the share, **attributed to the contributor's
   bucket**.

## Alternate / exception flows
- A1: A contributor **names** themselves → their uploads group under a **sealed-name bucket** scoped to
  this share.
- E1: A **VIEWER** tries to upload → `403` (read-only).
- E2: A guest tries to **delete** or write the journal → forbidden; contributors add, never remove.
- E3: A guest tries to attach a blob **not uploaded through this share** (e.g. an owner-scope blob) →
  `403` (blob not from share).
- E4: **Cross-share** upload-id reuse → `404` (owner-tag mismatch isolates shares).

## Quality notes
- *Confidentiality (paramount):* the operator sees the role, sealed metadata and ciphertext blobs —
  never plaintext content or guest identity.
- *Isolation:* contributions are tagged **per share**, so co-guests of the same link can't read or
  overwrite each other's in-flight uploads.
- *No deletes by design:* guests cannot destroy a curator's content; the worst case is **additive**.

## Related
- Feature: [Sharing](../_index.md) · Actors: [Memory Curator](../../../actors.md),
  [Public Contributor](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (share keys, share-boundary T&S)
- Decisions: [AD-03](../../../decisions/README.md) (per-subtree keys),
  [AD-05](../../../decisions/README.md) (T&S); ADR 0011 (persisted shares)
- Related use-cases: `uc.sharing.share-public-viewer`, `uc.sharing.collect-uploads`, `uc.uploads.unified-media`
