---
id: cr-0100
title: Public viewer share — the share resource + the anonymous no-account viewer (web vertical)
status: done
satisfies: [uc.sharing.share-public-viewer]
adrs: ["0029", "0012", "0015", "0013", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the first **sharing** slice ([uc.sharing.share-public-viewer](../docs/features/sharing/use-cases/uc.sharing.share-public-viewer.md),
[ADR 0029](../docs/decisions/0029-public-viewer-share.md)): a curator turns **one of their own (personal-scope)
Memories** into a **read-only, anonymous, revocable public link** `/s/{shareId}#k=<K_share>`, and anyone with the
link opens that one Memory in a browser — **no account, no install** — while the server stays operator-blind. The
crypto is the existing per-Memory second wrap `symWrap(K_share, K_mem)` ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md));
`K_share` rides the **URL fragment** ([ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md)) and never
reaches the server. The new work is the **share resource**, the **unauthenticated resolve + gated presigned blob
read**, soft (L1) revoke/expiry, and the curator + anonymous-viewer web surfaces. **Web vertical only; native is a
later CR.** **v1 is deliberately narrow: personal-scope Memories, VIEWER role only** — contributor/uploader writes,
password protection, and group/tenant sharing are separate later slices.

## Context & constraints

- Durable truth: [uc.sharing.share-public-viewer](../docs/features/sharing/use-cases/uc.sharing.share-public-viewer.md)
  (the flow + the 6 acceptance criteria), [ADR 0029](../docs/decisions/0029-public-viewer-share.md) (the share
  resource + anonymous access path + the trust-tier honesty + the *scope-rotation ≠ share-revoke* note),
  [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (the `symWrap(K_share, K_mem)` keying),
  [ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md) (fragment key),
  [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) (the presigned direct-to-store blob
  path the gated share-read reuses), [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) (why the operator-
  served no-install viewer is passive-safe → the footer-link honesty).
- Reused substrate (not rebuilt): the **v1 crypto suite** (`symWrap`/`symUnwrap`, `randomBytes` — no new
  primitive, so **no new conformance vector**); the **blob store presign** (`BlobStore`, ADR 0013); the web
  **content read model** (node tree + media metadata under `K_mem`) and the **gallery/lightbox** components (reuse
  read-only); the base64 wire rules ([web CLAUDE.md](../web/CLAUDE.md) — opaque fields are standard base64,
  fragment key is base64url in the URL only).
- Invariant at risk: **operator-blind-e2ee.** New server-visible surface is all opaque/operational — `shareId`,
  the wrap, the plaintext `role`, the `K_share`-sealed name, `nodeId`. The server must **never** receive `K_share`
  (fragment only), must gate blob reads to the share's subtree **structurally** (opaque ids, no name/content), and
  the anonymous resolve must expose **only** that one Memory subtree — never `K_SC`, a sibling Memory, or the
  scope. The T&S "decryptable-on-report" corollary ([security model](../docs/crosscutting/security-model.md)) is
  intended, not a leak.
- **v1 gate:** only a **personal-scope** Memory the caller owns may be shared — the create endpoint rejects a
  group/tenant node (the who-may-publish governance + the rotation-vs-revoke interaction are deferred, ADR 0029 §6).

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — new `contracts/api/sharing.openapi.yaml`: **authenticated** curator ops — `POST /api/shares`
  `{ nodeId, role: VIEWER, wrap, sealedName, expiresAt? }` → `{ shareId }`; `GET /api/shares` (list my shares);
  `POST /api/shares/{shareId}/revoke` (or `DELETE`) → disable. **Unauthenticated** guest ops — `GET
  /api/shares/{shareId}` → `{ role, wrap, sealedName, nodes[], media[] }` (the sealed subtree under `nodeId`, all
  still under `K_mem`) or `404`; `GET /api/shares/{shareId}/blobs/{blobId}` → a presigned store GET (or `302`) when
  the blob is attached under the shared subtree, else `404`. No new crypto vector (`symWrap` is existing, ADR 0009-
  pinned). · Done when: `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [x] 2 backend — persist a `veyq_share` row (`shareId` 256-bit unguessable PK, owner account, `node_id`, `role`,
  `wrap` blob, `sealed_name` blob, `enabled`, `expires_at`, `created_at`; migration V-next). **Create/list/revoke**
  (authenticated; create **gated to a personal-scope node the caller owns** — reject a group/tenant node). The
  **unauthenticated resolve** — return the record + the sealed subtree under `node_id`; `404` on
  disabled/expired/unknown; **no auth, no scope-filter relaxation** (a parallel authz path on share state). The
  **share-scoped gated blob GET** — authorize iff `blobId` is attached under the share's subtree (structural id
  check), return a presigned store GET; `404` otherwise / when disabled. Slice tests: create→resolve returns the
  subtree; disabled/expired→`404`; blob gate allows in-subtree + `404`s out-of-subtree; a non-personal node create
  is rejected; resolve leaks **nothing** outside the subtree. · Done when: `./gradlew test` green.
- [x] 3 http — `http/sharing/sharing.http`: create → **resolve with no bearer** → gated blob GET → revoke →
  resolve `404` → expiry `404` → out-of-subtree blob `404`, with response-handler assertions against a seeded
  instance. · Done when: the `.http` assertions pass.
- [x] 4 web (curator) — a **Share** affordance on a personal-scope Memory (MemoryDetail header): a dialog that
  generates a 32-byte `K_share`, computes `symWrap(K_share, K_mem)`, **re-seals the Memory name under `K_share`**,
  `POST`s the share, and shows the copyable link `/s/{shareId}#k=<base64url(K_share)>` (fragment built client-side,
  never sent). Plus a **manage-shares** list (the caller's shares for that Memory) + **revoke**. i18n EN+DE.
  · Done when: `npm run check` green.
- [x] 5 web (anonymous viewer) — a **no-account public route** `/s/:shareId`: read the fragment key, `GET
  /api/shares/{shareId}`, **unwrap `K_mem`** with the fragment key, decrypt names/metadata locally, and render the
  Memory **read-only** (reuse the gallery + lightbox in a read-only mode; media via the gated blob GET →
  direct-from-store). A **footer link to veyq's security documentation** (the trust-tier honesty, ADR 0026 — no
  explicit in-viewer warning). Handle `404` (revoked/expired/unknown) and a missing/invalid fragment key gracefully.
  i18n EN+DE. · Done when: `npm run check` green + a **live smoke** (create a share, open the `/s/` link in a fresh
  no-session context → read-only render + media, then revoke → `404`).
- [x] 6 coverage/docs — fill [uc.sharing.share-public-viewer.yaml](../docs/coverage/features/sharing/use-cases/uc.sharing.share-public-viewer.yaml):
  add test refs for all six criteria (backend slice + http + web unit), keep **apple/android** as gaps, and **drop
  `status: planned`** (all criteria of this use-case are built on web; native is parity, not a criterion). Mark the
  use-case **built (web)** in [sharing/_index.md](../docs/features/sharing/_index.md). · Done when:
  `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py` green.

## Definition of done

A curator can **turn a personal-scope Memory into a public link** `/s/{shareId}#k=…`, an **anonymous visitor with
no account** opens it in a browser and sees **that one Memory read-only** (names, metadata, media — all decrypted
client-side from the fragment key), and the curator can **revoke** it (or it **expires**) → the link `404`s.
**Operator-blind throughout:** the server holds only `shareId` + the wrap + the plaintext role + the sealed name +
`nodeId`, never `K_share`/`K_mem`/names/the viewer/which media was opened; blobs go **direct-from-store** via
share-gated presigns; the share exposes **only** that one Memory subtree (`K_SC` and siblings stay sealed). The
no-install viewer carries the trust-tier honesty as a **footer link** to the security docs. Guards green (`./gradlew
test`, the `.http` assertions, `npm run check`, and the four `docs/` validators). **Out of scope (follow-ups):**
contributor/uploader shares; password protection; **group/tenant public-sharing** (governance + the rotation-vs-
revoke interaction, ADR 0029 §6); persisted-shares/person-resolution; notices/tenant-terms; the foreign-operator-
proof no-install viewer (trusted-web / SaaS); **native parity** (web-first, like the rest of the epics).

## Retrospective

Shipped the full web vertical exactly as scoped, no new crypto primitive / conformance vector (the existing
`symWrap`/`symUnwrap`/`randomBytes` carry the whole feature). All six criteria green across the backend slice,
the http guest boundary, and the web unit round-trip.

**What landed:**
- **Contracts** — `contracts/api/sharing.openapi.yaml`: the authed curator ops (`createShare`/`listShares`/
  `revokeShare`) + the unauthenticated guest ops (`resolveShare`, `resolveShareBlob`). The **shareId is a 256-bit
  base64url string, not a UUID** (ADR 0029 pins 256-bit unguessable — a 122-bit UUID would be weaker), so the path
  param is a `^[A-Za-z0-9_-]{43}$` pattern. Both codegens clean.
- **Backend** — `features/sharing/` (Entity/Store/Handler/Resource) + `V31__share.sql`. The class is
  `@Authenticated` with the two guest methods `@PermitAll` (method-level override, the TenantsResource pattern);
  create is gated to a **personal-scope MEMORY the caller owns** (422 `illegal_scope` otherwise). The subtree walk
  + blob-authz set reuse the `NodesStore.deleteSubtree` BFS shape; `shareId` is minted by the existing
  `Tokens.random()` (32 bytes base64url). 12-test `ShareSliceTest`, `./gradlew check` green.
- **http** — `http/sharing/sharing.http`: real assertions on the genuinely-public guest boundary (an unknown
  well-formed share → `404 share_not_found`, **not 401** — proving `@PermitAll` + non-enumerability) + the curator
  auth walls. Verified live with `ijhttp` against a throwaway H2-less instance on a spare port (5/5 pass). The full
  authed create→resolve→revoke happy-path stays at the integration layer (`ShareSliceTest`), consistent with how
  content/media/scopes `.http` handle the operator-blind grant flow.
- **web (curator)** — a Share button on a personal-scope Memory's `MemoryDetail` header → `ShareDialog.vue`
  (generate `K_share`, wrap `K_mem`, re-seal the name, POST, show the copyable `/s/{id}#k=…` link + a manage/revoke
  list). The link fragment is built client-side and never sent.
- **web (viewer)** — a no-session `/s/:shareId` route (`ShareView.vue`) in a new full-bleed `layout: 'plain'`
  branch; reads the fragment key, resolves + decrypts locally, renders a read-only gallery + lightbox, with the
  ADR-0026 security-doc **footer link**. i18n EN+DE.
- **Shared components (so a share looks *exactly* like the internal Memory, like legacy `PublicShareView`).** The
  viewer reuses the **same components** as the internal gallery, not just the same CSS: a new presentational
  **`MediaGrid.vue`** (the justified-layout engine — container measurement + `calculateJustifiedMediaLayout` +
  positioned tiles via a generic `tile` scoped slot) now backs BOTH `MemoryDetail` and `ShareView`; and
  **`MediaLightbox.vue`** was made reuse-ready — a `readonly` flag hides the curator affordances (set-cover,
  capture-time edit) and optional `previewLoader`/`originalLoader`/`exifLoader`/`metaResolver` inject the data
  path, defaulting to the authed media API so the internal caller is byte-for-byte unchanged. `ShareView` mounts
  the same `MediaLightbox` in `readonly` mode with the operator-blind per-item share loaders. `npm run check`
  green (480 unit tests, incl. the unchanged 43 MemoryDetail/optimistic/MediaLightbox tests + the new crypto
  round-trip); a live browser diff confirmed the viewer grid + lightbox are pixel-identical to the internal ones
  (only the curator-only buttons absent).

**Design decisions made during the build:**
- **Chapter names aren't returned in the resolve** — a node's `encryptedName` is sealed under `K_SC`, which a
  public viewer never holds. The root Memory's name comes from the `K_share`-sealed `sealedName`; the subtree nodes
  carry only `wrappedContentKey` (to derive each chapter's `K_mem`) + `wrappedMeta`. Media across the whole subtree
  render in one flat read-only gallery under the Memory name — faithful to "that one Memory, read-only" without
  leaking `K_SC`-sealed data.
- **Two client factories** — `sharesApi()` (bearer, curator) vs `publicSharesApi()` (no token, the anonymous
  viewer) so the no-account page never sends an Authorization header.

**Live browser smoke — passed (full happy-path).** Against an isolated stack (a CLI backend + Vite preview on
`veyq.localhost:8089`, store `PUBLIC_ENDPOINT` matched to the browse origin, shared MariaDB + Garage): logged in
the `.run/.env` curator → created a personal Memory "Rom 2026" → uploaded a photo (real ciphertext to the store,
thumbnail decrypted back) → the **Share** button appeared → the dialog minted `/s/{43-char shareId}#k=<key>` →
opened the link **anonymously** and saw the read-only render: the Memory name (decrypted from `sealedName` with
the fragment key) + the decrypted image, driven purely by the unauthenticated path (`GET /api/shares/{id}` 200 →
gated `…/blobs/{id}` 200 → direct-from-store `/veyq/…` 200 — no bearer, `publicSharesApi`) → **revoked** →
reopened the same link → the honest "nicht verfügbar" (404) state. All six criteria confirmed end-to-end in a
real browser.
