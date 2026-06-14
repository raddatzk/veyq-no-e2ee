# UC: Organize media into buckets

- **ID:** uc.memory.organize-buckets
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Contributor
- **Goal:** Organize one's uploaded media into per-uploader buckets and sub-buckets within a Memory.
- **Preconditions:** Authenticated or share-authorized to upload to the Memory; holds the keys.
- **Postconditions (success):** The actor's media are filed into their own bucket(s)/sub-bucket(s),
  attributed to them; the unified gallery still spans all buckets and chapters.

## Node model
- Every Memory — **including each chapter** — groups its media into per-uploader **buckets**.
- Each uploader has a **default bucket** and may create **sub-buckets** (e.g. to file externally
  received photos separately). Buckets are an **attribution/organization** axis, orthogonal to chapters.

## Main flow
1. The actor **uploads directly into** a chosen bucket/sub-bucket (their default if none chosen).
2. The actor **creates, renames and reorganizes their own** buckets and moves their own media between
   them.
3. The actor always sees **their own** bucket — even when empty (so they can upload into it first).
4. Other members see a user's bucket **only once it holds content**; an **empty** bucket is visible
   only to its owner.
5. The **unified gallery** spans **all buckets and all chapters**, ordered by `takenAt`; buckets are an
   overlay, not a separate timeline.

## Alternate / exception flows
- A1: In an uploader/contributor share, the uploader **enters a name** on entry → that names their
  bucket (see [collect-uploads](../../sharing/use-cases/uc.sharing.collect-uploads.md)).
- A2: The Memory **owner** exercises their only special rights over others' buckets — **delete** and
  **move** ([AD-10](../../../decisions/README.md)); they **cannot** rename or restructure another user's
  bucket.
- E1: The actor tries to modify **another user's** bucket (beyond the owner's delete/move) → rejected.

## Quality notes
- *Ownership model:* each uploader fully controls **their own** buckets; the Memory owner has **no**
  special rights except **delete and move** (AD-10). No cross-user bucket management.
- *Visibility:* an empty bucket is **private to its owner** and becomes visible to others once it holds
  content — the bucket layer is always present without exposing empty placeholders.
- *Attribution:* the bucket is the **attribution unit** for uploads — consumed by
  [collect-uploads](../../sharing/use-cases/uc.sharing.collect-uploads.md) and the export naming in
  [download-zip](uc.memory.download-zip.md).
- *Not addressable:* buckets are a **UI organization/filter overlay**, **not** permalink nodes (unlike
  folders/Memories/chapters) — they need no slug tokens. Bucket names are namespaced **per uploader**,
  so two uploaders may each have an "external" sub-bucket.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md), [Public Contributor](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (scope keys)
- Decisions: [AD-10](../../../decisions/README.md) (deletion & move privilege)
- Related use-cases: `uc.uploads.unified-media`, `uc.sharing.collect-uploads`, `uc.memory.download-zip`
