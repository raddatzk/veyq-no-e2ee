# Feature: Sharing

Reaching people **outside** a scope with **one Memory**. Sharing *with* a registered veyq user is what
[groups / scope grants](../spaces/_index.md) already do; sharing's own job is the **public link** — handing a
single Memory to anyone, including **anonymous visitors with no account**. It rides the per-Memory content key
`K_mem` ([ADR 0012](../../decisions/0012-content-key-hierarchy.md)): a share adds an **independent second wrap**
of that one Memory's key, so it exposes **neither the rest of the scope (`K_SC`) nor any sibling Memory** —
least-privilege is crypto-enforced, and the server stays blind.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (key hierarchy + the client-integrity
  trust tier + the T&S "decryptable-on-report" corollary for public shares),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md).
- **Decisions:** [ADR 0029](../../decisions/0029-public-viewer-share.md) (the public viewer share — the sealed
  per-share key + the anonymous access path), [ADR 0012](../../decisions/0012-content-key-hierarchy.md) (the
  `symWrap(K_share, K_mem)` share keying), [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md) (the
  key in the URL fragment), [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md) (gated
  presigned blob reads), [ADR 0026](../../decisions/0026-client-trust-tiers.md) (why an operator-served no-install
  viewer is passive-safe, not operator-proof).

## Use-cases

- [uc.sharing.share-public-viewer](use-cases/uc.sharing.share-public-viewer.md) — **built (web)** (cr-0100: publish
  one of your own Memories as a read-only, anonymous, revocable public link `/s/{shareId}#k=…`; the share key rides
  the fragment, the server stays blind; v1 = **personal-scope Memories, VIEWER only**; native is later parity)
- [uc.sharing.share-contributor](use-cases/uc.sharing.share-contributor.md) — **built (web)** (cr-0101: a
  **CONTRIBUTOR** link where an anonymous visitor can **view + add photos** after naming themselves — the name
  **sealed under `K_mem`** → an anonymous share identity, the buckets hook; direct-append, personal-scope only;
  [ADR 0030](../../decisions/0030-contributor-public-share.md); native is later parity)
- [uc.sharing.share-uploader](use-cases/uc.sharing.share-uploader.md) — **built (web)** (cr-0102: an **UPLOADER**
  write-only drop-box — add photos but **cannot see** the Memory or others'; the resolve returns no media (the
  `canView` gate, no server-side gallery filtering), the own-view is **client-local, session-only** from local
  previews (never a download), delete-your-own is **token-gated + ownership-checked**, and the ephemerality is
  warned in four places; personal-scope only; [ADR 0031](../../decisions/0031-uploader-public-share.md); native is
  later parity)
- [uc.sharing.share-shared-scope](use-cases/uc.sharing.share-shared-scope.md) — **built (web)** (cr-0104: lifts
  the personal-scope-only restriction so **any member** of a **group/tenant** scope may public-share a Memory there,
  across all three roles; **member removal soft-revokes** the removed member's shares, **re-activation transfers
  ownership**; `K_mem` is stable across rotation so the share follows new content and the server-side deactivation
  is the honest-server revocation; [ADR 0029](../../decisions/0029-public-viewer-share.md) §6)

- [uc.sharing.share-note](use-cases/uc.sharing.share-note.md) — **built (web, cr-0115 + cr-0116)** (an optional
  **editable curator note** on a share, sealed under `K_share` + rendered (markdown) on the share page; **editable
  in place** via a `K_mem`-recoverable share key `sealed_share_key = symWrap(K_mem, K_share)` + an owner-gated
  `updateShare` — the link stays valid. **cr-0116** made the note **per-locale** (`{de,en}`, viewer picks locale),
  added an optional **private share name** (`sealed_share_name`, management-only), and **redesigned the dialogs**
  toward the legacy shape — list-only manage + separate create, copy-only links, with the **uploader as its own
  capability-gated memory action + dialog** (the same `ShareDialog`/`ShareCreateDialog` reused via an `uploader`
  flag — identical by construction, its only difference the absent role switcher). Deferred: the **anonymous
  acceptance gate** (cr-0117) + **password protection** (cr-0118) + native;
  [ADR 0036](../../decisions/0036-editable-share-note-recoverable-share-key.md))

## Deferred (later slices, each with its own dependency)

- **Buckets** — the per-uploader grouping (each contributor's uploads under their sealed name) is now refined as
  [uc.memory.buckets](../memory/use-cases/uc.memory.buckets.md) +
  [en.memory.buckets](../memory/enablers/en.memory.buckets.md)
  ([ADR 0034](../../decisions/0034-media-uploader-buckets.md)) — the contributor identity becomes the
  `share:{shareIdentityId}` **`owner_key`**, read with no migration. **cr-0113 (done)** shipped the full bucket
  data layer + backend + the **web** organizer (the always-visible Beiträge tab, sub-bucket create/rename/delete,
  move + batch, virtual-primary); a **camera filter** and the **native** surfaces stay later slices.
- **MANAGER role + moderation + persistent uploader deletes** — a manager role with view-all + edit/delete;
  **moderation** (review-before-publish) over the direct-append default; and persisting an uploader's id list
  client-side for **post-reload delete** (still no download).
- **Password-protected shares** — fold `Argon2id(passphrase)` into the wrap key for link-leak defense.
- **Persisted shares / person-resolution** — cross-scope surfaces. **Tenant terms** are now refined
  ([ADR 0035](../../decisions/0035-server-terms-of-service.md); the operator sets versioned per-locale terms in
  [uc.admin.tenant-terms](../admin/use-cases/uc.admin.tenant-terms.md), members accept server-side in
  [uc.account.accept-terms](../account/use-cases/uc.account.accept-terms.md)); what stays deferred **here** is the
  **anonymous share-page notice** — an accountless visitor's *client-side* (localStorage) acceptance on the public
  share page, a slice on top of those terms.
- **A foreign-operator-proof no-install viewer** — needs the future trusted-web client (SaaS-era).
