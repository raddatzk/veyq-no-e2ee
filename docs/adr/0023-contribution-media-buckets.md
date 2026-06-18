# ADR 0023: Per-contributor media buckets (Contributions)

## Status

Accepted (2026-06-18) — implementation in progress.

## Context

Legacy had a **Contributions** tab: media grouped per contributor into "asset buckets", with sub-folders,
moves, batch ops and a camera filter. The greenfield dropped it; the backend only tags each *blob upload* with
`BlobUpload.createdBy` (`"{userId}"` for members, `"share:{shareId}"` for guests) and **loses** that on
finalize — `Media` has no attribution. Kevin wants Contributions back on web **and** on mobile (it never
existed there), with these rules:

- Every user has a **virtual bucket** per Memory. It becomes **persistent** (and visible to others) the moment
  they upload into it — either targeted into the bucket or via the general Upload button.
- A user can create **sub-buckets** inside their own bucket (which also persists the user bucket) and upload
  into them specifically.
- A user may **move media only between their own buckets**. **Delete** and **capture-time correction** stay
  open to every key-holder (unchanged).
- A bucket renders the **normal justified gallery grid**; opening a tile gives the **normal lightbox**, paging
  within the **current bucket's** media. A bucket supports a **camera filter**.
- **Share uploads:** the guest names themselves once; all their uploads land in one bucket tied to that
  **anonymous identity**.

## Decision

### Crypto / privacy
Bucket names and the share-contributor display name are **user content → sealed under the scope content key**
(AEAD, same envelope as node names / journal). The operator stays blind to who/what a bucket is named. Member
display names are already known to the server for members of a tenant, but we still seal the *copy* stored on
the primary bucket so the contributions view is uniform and operator-blind.

### Contributor key
A stable string identifying who contributed a medium:
- Member → `user:{userId}`.
- Share guest → `share:{shareId}:{contributorId}`, where `contributorId` is a client-generated id the guest
  keeps (localStorage / app storage) so repeat uploads regroup. The guest's chosen name is the sealed name of
  their primary bucket.

This extends the existing `BlobUpload.createdBy` convention; `complete`/attach copies it onto the media.

### Data model
- `Media` gains **`created_by`** (contributor key, copied from the upload on attach) and **`bucket_id`**
  (nullable FK; null = the contributor's primary bucket, resolved lazily for display).
- New entity **`veyq_media_bucket`** (`V29`): `id`, `scope_id`, `node_id`, `owner_key` (contributor key),
  `parent_bucket_id` (nullable; null ⇒ primary/user bucket), `sealed_name` + `suite_id` (AEAD name; primary =
  contributor display name, sub = user folder name), `primary` (bool), `created_at`. Unique `(node_id,
  owner_key, parent_bucket_id, …)` is not enforced for sub-names (names are ciphertext); primary uniqueness is
  `(node_id, owner_key) where primary`.
- **Virtual-until-upload:** a primary bucket row is created lazily on the contributor's first upload into the
  node (or first sub-bucket creation). Contributors with no media have no row → don't appear.

### Endpoints (one resource/tag each — greedy-`@Path` aware, nested under the node)
- `GET    /api/scopes/{scopeId}/nodes/{nodeId}/buckets` — list persisted buckets `{id, ownerKey, parentId,
  sealedName, primary, mediaCount}` (key-holder).
- `POST   /api/scopes/{scopeId}/nodes/{nodeId}/buckets` — create a sub-bucket under the **caller's own** primary
  (sealed name); lazily persists the caller's primary. Body `{sealedName, parentBucketId?}`.
- `PATCH  /api/scopes/{scopeId}/nodes/{nodeId}/buckets/{bucketId}` — rename (own buckets only; re-seal).
- `DELETE /api/scopes/{scopeId}/nodes/{nodeId}/buckets/{bucketId}` — delete own **sub**-bucket; its media fall
  back to the owner's primary bucket (`bucket_id = null`). Primary buckets are not deletable.
- `createMedia` / share `contributed-media` accept an optional **`bucketId`** (default = caller's primary,
  created lazily). The contribute endpoints also accept the guest **`contributorId` + sealed display name**.
- Media **move**: `updateMedia` (`PATCH .../media/{mediaId}`) gains **`bucketId`** — server enforces the medium
  and the target bucket are **both owned by the caller** (`media.created_by == caller` and
  `bucket.owner_key == caller`).

### Permissions
- Create/rename/delete bucket, move media: **own buckets only** (`owner_key` == caller's contributor key).
- Delete media, edit capture time: **any key-holder** (unchanged from today).
- Guests act under their share's contributor key, gated to the shared subtree (existing share ACL).

### UI (web + iOS + Android)
The **Contributions tab** lists buckets (primary per contributor, expandable to sub-buckets) with sealed names
+ media counts; selecting a bucket shows the **normal gallery grid** filtered to that bucket, with the **normal
lightbox** paging within the bucket. Own buckets show create/rename/delete + a per-tile "move to my bucket".
The **camera filter** within a bucket depends on camera metadata in `sealedMeta` (**ADR 0024 / task #25**) —
it ships once that lands; until then the filter is hidden.

## Consequences
- The server learns the **contributor key** per medium (it already knew it per upload) and the **bucket
  structure** (counts, parentage) — but **never** the bucket/contributor *names* (sealed). Consistent with the
  operator-blind model.
- Dedup unaffected: buckets are an attach-time grouping, not a storage concept; the same blob can back media in
  different buckets.
- Mobile gains a feature it never had; the bucket grid/lightbox reuse the existing gallery + lightbox so the
  per-platform cost is mostly the bucket list + move/create/rename controls.
- Migration `V29` adds the bucket table + the two `Media` columns; existing media get `created_by` backfilled
  to the scope owner and `bucket_id = null` (→ owner's primary bucket).
