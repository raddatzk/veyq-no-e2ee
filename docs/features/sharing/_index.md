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

## Deferred (later slices, each with its own dependency)

- **Buckets** — group each contributor's uploads under their (sealed) name (the contributor share stores the
  `share_identity` link as the forward-compat hook; this is the grouping/display UI on top).
- **MANAGER role + moderation + persistent uploader deletes** — a manager role with view-all + edit/delete;
  **moderation** (review-before-publish) over the direct-append default; and persisting an uploader's id list
  client-side for **post-reload delete** (still no download).
- **Password-protected shares** — fold `Argon2id(passphrase)` into the wrap key for link-leak defense.
- **Public-sharing of group / tenant Memories** — a governance policy (who may publish shared content) plus the
  *scope-rotation ≠ share-revoke* interaction ([ADR 0029](../../decisions/0029-public-viewer-share.md) §6).
- **Persisted shares / person-resolution**, **share notices / tenant terms** — legal + cross-scope surfaces.
- **A foreign-operator-proof no-install viewer** — needs the future trusted-web client (SaaS-era).
