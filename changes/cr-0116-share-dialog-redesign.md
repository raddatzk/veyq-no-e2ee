---
id: cr-0116
title: Share dialog redesign — list-only + separate create, uploader as its own action, share name, per-locale note
status: done
satisfies: [uc.sharing.share-note]
adrs: ["0036"]
invariants: [operator-blind-e2ee]
---

## Goal

Bring the curator's share UI to the legacy pre-E2EE shape (the product-UX source), operator-blind. The share
dialog becomes **list-only + a "New" button** that opens a **separate create dialog**; the **uploader (drop-box)**
becomes **its own memory action** (not a share-type in the switcher); the manage list is **copy-only** (no visible
URL); a share carries an **optional private name**; and the curator note is **per-locale** ([ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md),
[uc.sharing.share-note](../docs/features/sharing/use-cases/uc.sharing.share-note.md)). **Password protection**
(operator-blind, Argon2id→wrap) and the **anonymous acceptance gate** (per-locale note + tenant terms as an
accept-before-view) are deliberately **later CRs**.

## Context & constraints

- **Operator-blind (invariant) — unaffected.** The new **share name** is sealed under `K_share` (AAD `share-name`,
  a new opaque column `sealed_share_name`), **management-only** — never served to the viewer (`PublicShare` carries
  no name). The note stays `sealedNote` under `K_share`; only its **plaintext** changes from a raw string to a
  **per-locale JSON map** `{ "de": …, "en": … }` — no new field, no server change to the note. Both are edited via
  the `K_mem`-recoverable `K_share` (cr-0115). Recorded in the [security-model](../docs/crosscutting/security-model.md).
- **List-only + separate create.** `ShareDialog` shows only VIEWER/CONTRIBUTOR shares (uploader filtered out) + a
  "New" button → `ShareCreateDialog` (name, a **VIEWER/CONTRIBUTOR** switcher, a per-locale note editor). Editing a
  share reuses the same dialog (name + note), replacing cr-0115's inline note editor. `updateShare` is now a
  **full replace** of the two editable fields (`sealedNote` + `sealedShareName`).
- **Uploader is its own action.** A separate `pi-user-plus` button in the memory action bar (gated on the
  `sharing.uploader-links` capability, [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md))
  opens `UploaderShareDialog` (list + inline create, **reduced fields**: name only, role fixed UPLOADER). The
  create switcher therefore only offers VIEWER/CONTRIBUTOR — uploader was removed from it.
- **Copy-only links.** The manage list shows a per-row **copy** button (the link reconstructed via the
  `K_mem`-recoverable `K_share`, cr-0115) and no raw URL — a deliberate simplification from legacy (which showed
  both).
- **Per-locale note.** A ported, compact `LocaleMarkdownEditor` (de/en) authors the note map; the share page picks
  the viewer's locale (exact → base → `en` → first), rendered with the cr-0114 markdown renderer.
- **Verify:** backend `./gradlew check`; web `cd web && npm run check`; the three trust-chain guards; a live dialog
  smoke (list-only + separate create + uploader-as-action + name + per-locale note).

## Phases

- [x] 1 **contracts** — `CreateShareRequest`/`ShareSummary`/`UpdateShareRequest` += **`sealedShareName`** (opaque
  base64, `maxLength 512`); `sealedNote` `maxLength` raised to 16384 (it now carries a per-locale JSON map);
  `sealedShareName` **not** on `PublicShare` (management-only). Regen both clients. · **Done:** codegen green both
  sides.
- [x] 2 **backend** — V38: `veyq_share += sealed_share_name` (TEXT, nullable). `SharingStore.create` stores it;
  `listShares` serves it; `updateNote` → **`updateShare(sealedNote, sealedShareName)`** (owner-gated full replace).
  · **Done:** `./gradlew check` green (**285 tests**) — `ShareNoteSliceTest` extended: a name shows in the manage
  list but never on the viewer resolve; a rename via `updateShare` changes the name + keeps the note.
- [x] 3 **web** — `share.ts`: per-locale note (`sealNoteMap`/`openNoteMap` + `pickLocaleText`), `sealShareName`/
  `readShareName`, `updateShare(note-map, name)`, `recoverShareLink` (cr-0115). New components: `LocaleMarkdownEditor`,
  `ShareCreateDialog` (create + edit), `UploaderShareDialog`; `ShareDialog` rewritten list-only (copy-only, name as
  the row title, edit/revoke). `MemoryDetail` gains the capability-gated uploader action → `MemoryHomeView` mounts
  `UploaderShareDialog`. `ShareView` renders the viewer-locale note. i18n `share.*`. · **Done:** `npm run check`
  green (typecheck + **550 tests** + build); a full live dialog smoke passed (see status note).
- [x] 4 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries `sealedShareName`
  after regen; the native share UI is each platform's own later CR.

## Status note (implemented 2026-07-10)

Shipped **contracts → backend → web**, all green:
- **Contracts:** `sealedShareName` on create/summary/update (not on the viewer resolve); the note field widened for
  the per-locale map.
- **Backend:** V38 + `SharingStore.updateShare` (owner-gated full replace of note + name), name served in the
  manage list only. `./gradlew check` = **285 tests, 0 failures**.
- **Web:** the redesigned dialogs (`ShareDialog` list-only, `ShareCreateDialog` create/edit, `UploaderShareDialog`),
  `LocaleMarkdownEditor`, the per-locale note + name in `share.ts`, the capability-gated uploader action in
  `MemoryDetail`, and the viewer-locale render in `ShareView`. `npm run check` green (550 tests, incl. the rewritten
  `share.test.ts` note+name round-trips + a `ShareCreateDialog` switcher test proving the uploader is absent).

**Live smoke (screenshot-proven).** Owner opens a Memory → two separate actions **"Teilen"** + **"Upload anfragen"**.
"Teilen" → a **list-only** dialog (existing shares, a **"New" button**, **no inline form**, **copy-only** rows with
copy/edit/revoke). "New" → a **separate** create dialog (name, a **Nur ansehen / Ansehen + Fotos** switcher — no
uploader, a **per-locale** note editor "+ Deutsch/Englisch hinzufügen"). Created a share with the name "Für Oma" + a
German note → the list row shows **"Für Oma"** as its title + a **"Hinweis"** badge (older shares fall back to the
role). "Upload anfragen" → a **separate** dialog with **reduced fields** (name only) + "Upload-Link erstellen".

**Deferred (later CRs, chosen with the user):**
- **cr-0117 — anonymous acceptance gate** (the "gate wie Legacy"): the share page shows the SHARE note + the TENANT
  terms as an **accept-before-view** gate (client-side/localStorage), reviving the cr-0114 anonymous notice — needs
  a resolve-contract change; to be refined first.
- **cr-0118 — password-protected shares** (operator-blind: `Argon2id(passphrase)` folded into the wrap key, **not**
  the legacy server-check).
- **native** share UI (apple/android).
