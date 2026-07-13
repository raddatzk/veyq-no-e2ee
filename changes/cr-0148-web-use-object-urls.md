---
id: cr-0148
title: Web object-URL lifecycle primitive — useObjectUrl / useObjectUrls + migrate the cleanly-owned sites (audit P3d)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Audit finding #74 (**P3d**, web shared async/lifecycle primitives) names `useObjectUrls` as its own CR: browser
object-URL lifecycle (`URL.createObjectURL` / `URL.revokeObjectURL`) is hand-rolled at ~7 sites, each re-deriving
the same revoke-on-replace / revoke-on-drop / revoke-on-unmount dance, and `memoryCover.loadNodeCover` mints a URL
it hands off **by contract** ("the caller owns the URL and must revoke it") with nothing structural enforcing it.

Build the missing primitive and migrate the sites whose ownership is **clean and self-contained**; fix the named
handoff. Explicitly leave the donation-tangled gallery thumbnail maps for the combined #77/#74 CR (they can't be
cut here without dragging in the deferred `useMediaGalleryLive` knot).

## Context & constraints

### The primitive — `web/src/shared/state/useObjectUrls.ts` (+ co-located `useObjectUrls.test.ts`)

Two Vue composables, matching the `shared/state/useXxx.ts` convention (`useThemeState`, `useViewMode`, …). Both
auto-revoke on `onBeforeUnmount`, so a consumer never writes its own unmount hook again.

- **`useObjectUrl()`** — a single owned slot. `{ url: Ref<string | null>, set(blob: Blob | null), clear() }`.
  `set(blob)` revokes the previous URL then mints + stores the new one; `set(null)` / `clear()` revokes + nulls.
  Fits the three single-slot owners below.
- **`useObjectUrls()`** — a keyed collection. `{ urls: Ref<Map<string, string>>, get(key), set(key, blob),
  drop(key), clear() }`. Exposes a **`Ref<Map<string,string>>`** — the exact reactive contract today's consumers
  and templates read (`urls.value.get(id)`), and that `useMemoryCover` receives as `ctx.thumbs`. `set` revokes a
  replaced key; `drop` revokes one; `clear` revokes all; each mutation reassigns `urls.value = new Map(...)` to
  keep the existing Map-churn reactivity. Fits the keyed thumbnail owners.

Both mint from a `Blob` (callers pass `new Blob([bytes])` as they do today) so the URL is *only ever created
inside the primitive* — that is what makes the handoff structural rather than by-contract.

### Phase 1 — migrate the 4 cleanly-owned sites (mechanical, no data-shape change)

- **`features/memory/useMemoryCover.ts`** — `uploadCoverUrl` ref + `revokeUploadCover()` + `onBeforeUnmount` →
  `const cover = useObjectUrl()`. The `watch(storedCover)` body: `revokeUploadCover()` → `cover.set(null)`, and
  `uploadCoverUrl.value = URL.createObjectURL(new Blob([bytes]))` → `cover.set(new Blob([bytes]))`; `coverSrc`
  reads `cover.url.value`. Drops the manual revoke fn + unmount hook.
- **`features/memory/components/MediaLightbox.vue`** — `previewUrl` ref + `revokePreview()` + unmount →
  `useObjectUrl()`; `loadPreview` calls `set(new Blob([bytes]))`.
- **`features/memory/components/MemoryCoverDialog.vue`** — `uploadObjectUrl` ref + `revokeUpload()` + unmount →
  `useObjectUrl()`; `onFilePicked` calls `set(...)` (re-pick auto-revokes the previous).
- **`features/memory/useMediaTrash.ts`** — `trashThumbs` + `setTrashThumb`/`revokeTrashThumbs` + the revoke half
  of `dropFromTrash` + unmount → `const trash = useObjectUrls()`. `setTrashThumb(id, url)` → `trash.set(id, new
  Blob([bytes]))` (mint moves inside the primitive); `dropFromTrash` revoke+delete → `trash.drop(mediaId)`;
  `revokeTrashThumbs()` (used by `loadTrash` + `resetTrash`) → `trash.clear()`. **Returned key name stays
  `trashThumbs`** (`return { trashThumbs: trash.urls, … }`) so the trash template binding is untouched. The
  cr-0147 trash test stays green (mint/revoke are mocked the same way).

None of these change a value shape or a template — pure internal swap; each sheds a hand-rolled revoke fn + unmount
hook.

### Phase 2 — fix the named "unmanaged handoff" (`memoryCover.loadNodeCover`)

`loadNodeCover` currently mints the URL and returns it in `LoadedCover { url, crop }` with a by-contract "caller
must revoke". Change it to return the **decrypted bytes** — `DecryptedCover { blob: Blob; crop }` — so the pure fn
never mints a URL and cannot leak. Its sole live caller, `components/MemoryBrowse.vue` (verified: one caller),
mints via the primitive:

- `const coverUrls = useObjectUrls()` keyed by `nodeId`; a small `Map<nodeId, CoverCrop>` for the crops.
- On resolve: `coverUrls.set(node.id, blob)` + record the crop; the `covers` the cards/`SpaceMap` read becomes a
  `computed` `{ url, crop }` per settled node (url from `coverUrls.urls`, crop from the crop map).
- `revokeCovers()` + its `onBeforeUnmount` collapse into `coverUrls.clear()` (+ clear the crop/tried sets); the
  primitive owns url revocation and unmount.

Update the `loadNodeCover` doc to drop the ownership caveat. This removes the last "future caller leaks unless it
reproduces the revoke" edge — the finding's actual target.

### Explicitly out of scope (stated so the omission is a decision, not a miss)

- **`MemoryDetail.thumbs` + `ShareView.thumbs` + `useOptimisticTiles` (donation)** — these carry `donatedThumbs`
  ownership-transfer semantics (a preview URL minted by `useOptimisticTiles` is *donated* to the gallery `thumbs`,
  which must NOT revoke until it takes over). That is the donation-tangled `useMediaGalleryLive` core #77 deferred
  and the share-viewer thumbnail dedup #74 deferred — folding it in here would drag that knot back in. Migrated
  in the combined gallery CR, on top of this primitive.
- **`features/memory/mediaDeriver.ts`** (2 sites) — already `finally`-scoped local URLs (poster/probe); no leak,
  no ownership to consolidate. Left as-is.

### Verify

`cd web && npm run check` (eslint + `vue-tsc` + test + build). Live-mount `veyq.localhost:8081` (per web/CLAUDE.md)
and confirm: a Memory cover (upload source) renders, the lightbox preview + cover dialog upload preview render, the
Trash tab thumbnails render, and the browse-card covers render — all with a clean console (no leaked-URL / broken
`<img>` warnings) after HMR.

## Phases

- [x] 1 web — added `useObjectUrls.ts` (`useObjectUrl` + `useObjectUrls`) + `useObjectUrls.test.ts` (9 cases);
  migrated the 4 cleanly-owned sites (`useMemoryCover`, `MediaLightbox`, `MemoryCoverDialog`, `useMediaTrash`) —
  each sheds its hand-rolled `revokeObjectURL` fn + `onBeforeUnmount`; the trash return stays `trashThumbs`
  (`= thumbs.urls`) so the template + the cr-0147 test are untouched. **Done:** `npm run check` green (595 tests,
  +9).
- [x] 2 web — `loadNodeCover` now returns `DecryptedCover { blob, crop }` (no URL minted in the pure fn);
  `MemoryBrowse` mints covers via `useObjectUrls` keyed by nodeId, pairs each with its crop in a `computed`
  display map, and drops its hand-rolled `revokeCovers` + `onBeforeUnmount`. Updated the `loadNodeCover` decrypt
  test to assert on the returned bytes. **Done:** `npm run check` green (595 tests, 4 e2e skipped — no backend).

## Definition of done

`npm run check` green; `shared/state/useObjectUrls.ts` exists with a unit test; the 4 clean owners + the browse
covers route their object-URL lifecycle through it (no hand-rolled `revokeObjectURL`/`onBeforeUnmount` left at
those sites); `loadNodeCover` no longer mints a handed-off URL. The donation-tangled gallery/share maps and
`mediaDeriver` are untouched and named as deferred/excluded. Operator-blind unaffected — object URLs are
decrypted-in-memory client state; the server sees nothing either way.

## Retrospective

- **Cut by ownership, not by count.** Of the 9 `createObjectURL` sites the audit map surfaced, only 6 were real
  candidates: `mediaDeriver` (2×) is already `finally`-scoped (no leak, no owner to consolidate) and the three
  donation-tangled thumbnail maps (`MemoryDetail.thumbs`, `ShareView.thumbs`, `useOptimisticTiles`) carry
  cross-composable ownership transfer that belongs with the deferred `useMediaGalleryLive` (#77) + share-viewer
  dedup (#74). Migrating those here would have dragged that knot back in — so they were named as excluded, not
  silently skipped.
- **The named handoff was the point.** #74 flags `loadNodeCover`'s by-contract "caller owns the URL". The fix
  wasn't to manage the URL better at the receiver but to stop minting it in a pure function at all — return bytes,
  let the one caller mint via the primitive. With a single live caller, the signature change was contained.
- **Verification honest about the blocker.** `npm run check` is fully green (595 tests, incl. 9 new primitive
  cases + the `loadNodeCover` test rewired to assert the decrypted-byte flow), and the live app shell mounts with
  a clean console after HMR. The visual media-render check (browse-card covers, lightbox preview, cover dialog,
  trash thumbnails) could **not** be exercised: the local backend was returning 500 ("Dein Bereich konnte nicht
  geöffnet werden") and the no-parallel-`quarkusDev` rule bars spinning up a second one. That check is left for a
  healthy backend — the unit coverage over the primitive + each migrated site's mint/revoke path is the standing
  guard in the meantime.
