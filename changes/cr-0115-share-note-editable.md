---
id: cr-0115
title: Share note — an editable curator message on a share (K_mem-recoverable share key)
status: done
satisfies: [uc.sharing.share-note]
adrs: ["0036"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a curator attach an **optional note** to a share — a greeting / instruction shown E2EE on the share page to
whoever opens the link — **set at creation and editable in place** (the link stays valid). The note seals under
the share's own **`K_share`** (per-share, least-privilege); to make it editable later, the share also stores a
**recoverable copy of `K_share`** sealed under `K_mem` (`symWrap(K_mem, K_share)`), so the curator — who always
holds `K_mem` — recovers `K_share` anytime to re-seal ([ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md),
[uc.sharing.share-note](../docs/features/sharing/use-cases/uc.sharing.share-note.md)).

## Context & constraints

- **Operator-blind (invariant) — unaffected.** The server gains two opaque columns: **`sealed_note`** (the note
  under `K_share`) + **`sealed_share_key`** (`symWrap(K_mem, K_share)`, openable only with `K_mem`, which the
  operator lacks). `K_share` is **never** stored in the clear; `updateShare` swaps one ciphertext for another. No
  key or plaintext crosses the boundary. Recorded in the [security-model](../docs/crosscutting/security-model.md).
- **Note keyed under `K_share`** (AAD **`share-note`**) — mirrors the existing `sealedName` (`sealName`/`openName`)
  byte-for-byte, just a distinct AAD context. Optional (no note → nothing sealed, no banner).
- **Recoverable key** `symWrap(K_mem, K_share)` (AAD **`share-key-recovery`**) — a new dedicated context, never
  reused. The curator recovers `K_share = symUnwrap(K_mem, sealed_share_key, "share-key-recovery")`.
- **`updateShare` is owner-gated + minimal.** `PATCH /api/shares/{shareId}` (`@Authenticated`, `owner_account`
  check → `403`), body `{ sealedNote }`. It does **not** touch `K_share`/`K_mem`/`role`/`wrap`/`shareId` — the
  capability's identity + grant stay immutable; only the curator's note changes. `shareId` + the link are
  unchanged. (The op is extensible to expiry / a re-sealed name later; v1 edits only the note.)
- **Reuse the cr-0114 markdown renderer** (`web/src/shared/markdown.ts`, escape-first safe subset) to render the
  note on the share page — a banner under the title (VIEWER/CONTRIBUTOR) or the primary instruction above the name
  field (UPLOADER).
- **All roles; personal-scope v1.** VIEWER / CONTRIBUTOR / UPLOADER all carry a note; the current personal-scope
  cut applies (rides [cr-0104](cr-0104-shared-scope-public-shares.md)'s group/tenant lift with no extra work).
- **Bonus fixed along the way:** the share-management list can now **show + edit every minted share's note** (it
  returns `sealed_note` + `sealed_share_key`, so the dialog recovers `K_share` via `K_mem`) — closing the current
  "note only visible from the fresh link" gap.
- **Verify:** backend `./gradlew check` (slice tests below); web `cd web && npm run check`; the three trust-chain
  guards; a live smoke (create a share with a note → open the share page, note renders → edit the note → the same
  link shows the new note; a non-owner edit refused).

## Phases

- [x] 1 **contracts** — `CreateShareRequest` += `sealedNote` (opaque base64, optional, `maxLength 8192`) +
  `sealedShareKey` (`symWrap(K_mem,K_share)`, base64, `maxLength 256`); `PublicShare` += `sealedNote` (nullable);
  `ShareSummary` += `sealedNote` + `sealedShareKey` (so the manage list can show/edit); new **`updateShare`**
  (`PATCH /api/shares/{shareId}`, `UpdateShareRequest { sealedNote }`). · **Done:** both fields + `UpdateShareRequest`
  + `updateShare` on `sharing.openapi.yaml`; codegen green both sides.
- [x] 2 **backend** — V37: `veyq_share` += `sealed_note` (TEXT, nullable) + `sealed_share_key` (TEXT, nullable);
  `SharingStore.create` stores both verbatim; `resolve` + `listShares` serve them; a `SharingStore.updateNote` +
  `SharingHandler.updateNote(userId, shareId, sealedNote)` (owner-gated) + `SharingResource.updateShare` `PATCH`. ·
  **Done:** `./gradlew check` green (283 tests) incl. `ShareNoteSliceTest` (create-with-note → resolve returns it;
  owner edits in place at the same shareId; null clears it; a non-owner edit is `404`; the manage list carries the
  recoverable key).
- [x] 3 **http** — the note lifecycle is covered hermetically by `ShareNoteSliceTest` (it needs the operator-blind
  grant flow + client crypto, like the existing create→resolve happy-path); `http/sharing/sharing.http` asserts the
  **`updateShare` auth wall** (`PATCH` without a bearer → `401`). · **Done:** `sharing.http` runs green (**7/7**)
  against the dev instance.
- [x] 4 **web** — `share.ts`: `sealNote`/`openNote` (AAD `share-note`) + `recoverShareKey` (AAD `share-key-recovery`)
  + `readShareNote`; `createShare` seals the note + always stores `sealedShareKey`; `updateShareNote` (recover
  `K_share` → re-seal → PATCH). `ShareDialog.vue`: a note textarea on create + a per-share **edit** affordance
  (decrypt the current note via the recovered `K_share`). `ShareView.vue`: render `resolved.note` (reuse the cr-0114
  `renderMarkdown`) as a banner / uploader instruction. i18n `share.note*`. · **Done:** `npm run check` green
  (typecheck + 549 tests + build); the curator create + manage-list + edit UI verified live (screenshot).
- [x] 5 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries the note +
  recoverable-key fields after regen, so the native surfaces are each platform's own later CR.

## Status note (implemented 2026-07-10)

Shipped **contracts → backend → http → web**, all green:
- **Contracts:** `sealedNote` + `sealedShareKey` on `CreateShareRequest`, `sealedNote` on `PublicShare`/`ShareSummary`
  (+ `sealedShareKey` on the summary), and a new owner-gated `PATCH updateShare`. Both clients regenerated.
- **Backend:** V37 (two nullable TEXT columns on `veyq_share`), `SharingStore.updateNote` + `SharingHandler.updateNote`
  (owner-gated 404) + `SharingResource.updateShare`; create/resolve/list thread the ciphertexts verbatim. `./gradlew
  check` = **283 tests, 0 failures**, incl. the 5-case `ShareNoteSliceTest` over the full HTTP stack.
- **http:** `sharing.http` **7/7** live (the `updateShare` 401 wall; the note lifecycle is in the slice test).
- **web:** the `share.ts` seal/recover/read helpers + `updateShareNote`, the `ShareDialog` note field + in-place
  editor, the `ShareView` note banner (reusing the cr-0114 markdown renderer), and the `share.note*` i18n. `npm run
  check` green; 4 new `share.test.ts` cases (seal-under-K_share + read round-trip via `resolveShare`, edit in place
  recovering K_share, clear-with-blank, `readShareNote` empty).

**Design highlights:**
- **Recoverable share key (the enabler).** Every share stores `sealed_share_key = symWrap(K_mem, K_share)`, so the
  curator recovers `K_share` via `K_mem` anytime to edit the `K_share`-sealed note in place — per-share binding **and**
  durable editability, operator-blind (`K_share` never in the clear). It also fixes the prior limitation: the manage
  list can now show/edit **every** minted share's note **and re-copy its full link** (`recoverShareLink` rebuilds
  `/s/{id}#k=<K_share>` from the recovered key) — not just the just-minted one. A pre-cr-0115 share (no recoverable
  key) degrades gracefully (no link/edit, only revoke).
- **Owner-gated, non-destructive `updateShare`.** Swaps one ciphertext for another; never touches
  `K_share`/`K_mem`/`role`/`wrap` — the capability's identity + grant stay immutable, the link stays valid.

**Live-smoke note.** The curator side (the share dialog's note field, the created share, the note in the manage list,
and the **Notiz bearbeiten** editor) rendered live and is screenshot-proven. The **/s/ share-page note render** could
not be screenshot on the running dev instance because the only reachable shareable Memory ("test") carries **leftover
cr-0113 demo data** — a hand-inserted non-UUID `share_identity_id` ("demo-oma-2026") that makes the *existing*
`SharingResource.toShareMedia` throw `Invalid UUID` on resolve (unrelated to this CR; real contributor ids are always
UUIDs). The share-page render is covered by the web suite (`resolveShare` returns the decrypted note) + `markdown.test.ts`.
A clean-Memory browser render remains a quick follow-up once that demo row is cleared.

**Still open (later slices):** the **native** editor + share-page render (apple/android).

## Definition of done

A curator can attach an **optional, editable note** to a VIEWER / CONTRIBUTOR / UPLOADER share; it seals under
**`K_share`**, is **rendered** (markdown) on the anonymous share page, and is **editable in place** (via the
`K_mem`-recoverable `sealed_share_key` + an owner-gated `updateShare`) **without changing the link** — all
**operator-blind** (only ciphertext stored; `K_share` never in the clear). Ships **contracts → backend → http →
web**; `./gradlew check` + `npm run check` + the three guards green; a live create→render→edit smoke passes. The
**native** surfaces remain a later slice.
