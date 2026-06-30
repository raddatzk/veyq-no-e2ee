---
id: cr-0060
title: Chapters foundation — nested Memories with a recursive per-node K_mem (web/backend)
status: done
satisfies: [uc.memory.chapters]
adrs: ["0012", "0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Surface **chapters** — sections *inside* a Memory, each itself a full Memory that may nest further — in the web
client, on the contract/backend model that already defines the `CHAPTER` node kind. The crux is the **key
model**: a chapter carries its **own random `K_mem`**, wrapped under its **parent's content key** (recursive
`K_mem`, no new key type), and media on a chapter is sealed under the **chapter's** `K_mem`. This makes every
content node a future **sharing unit** without a re-encryption migration — but sharing itself stays out.

## Context & constraints

- **Resolve [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)'s deferred chapter-key question.** The
  ADR left open whether a chapter shares its Memory's `K_mem` or gets its own (tentative: shares). We decide
  **own random `K_mem`, wrapped under the parent Memory's `K_mem`** (a top-level Memory's stays wrapped under
  `K_SC`). Same `content-key` wrap context, applied recursively — **not** a new `K_chapter` type and **not** the
  full per-subtree key tree the ADR rejected (folders stay key-less). Update ADR 0012 to record this.
- **`K_blob` under the *direct* parent's `K_mem`.** Media on a chapter seals its `K_blob` under the chapter's
  `K_mem`; on the Memory under the Memory's `K_mem`. Moves are a single re-wrap (media = its `K_blob`; a whole
  chapter between Memories = its `K_mem`), never a data re-encrypt — out of scope here, but the layout allows it.
- **Backend is mostly ready.** `CHAPTER` exists; `parentAllows` already enforces CHAPTER-only-under-MEMORY. The
  gaps: `createNode`'s `wrappedContentKey` is documented/treated as MEMORY-only — **allow it on CHAPTER too**
  (contract wording + any backend guard), and `listNodes` already returns it.
- **Web key plumbing.** `tree.ts` `createMemory`/`openMemory` wrap/unwrap `K_mem` under `K_SC`. Generalize to
  **"under the parent node's content key"**: `createChapter(parentMemoryKey, …)`, and opening a chapter unwraps
  its `K_mem` under the parent's `K_mem`. Names stay under `K_SC` (whole-tree navigation unchanged). The client
  must walk the `K_mem` chain (K_SC → Memory `K_mem` → chapter `K_mem`) to open nested content.
- **Reuse the design source** (`legacy/frontend-noe2ee/`, `uc.memory.create-tree`) for the chapter affordance +
  nested gallery; only the data layer is ours.
- **Scope.** Web only. **Deferred:** aggregated chronological gallery (a later CR), media metadata/EXIF (a later CR),
  sharing (#19), map/tracks/playlists/journal/buckets, native chapter UI.

## Phases

- [x] 1 key model + contract — update [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (chapter =
  recursive `K_mem`); allow `wrappedContentKey` on a `CHAPTER` in `content.openapi.yaml` (wording +, if present,
  the backend MEMORY-only guard); regenerate. · Done when: `./gradlew check` green; a slice test creates a
  CHAPTER under a MEMORY carrying a `wrappedContentKey` and lists it back (opaque, server never inspects).
- [x] 2 web data layer — generalize `tree.ts` to a recursive content-key model: `createChapter` (births a
  random `K_mem`, wraps it under the parent Memory's `K_mem`), `openMemory`/`openChapter` unwrap under the
  parent's key, and a helper to resolve a node's `K_mem` by walking the parent chain from `K_SC`. · Done when:
  `npm run check` green; `tree.test.ts` round-trips a chapter's `K_mem` (wrapped under the Memory key, opened
  back) and rejects opening with the wrong parent key.
- [x] 3 web UI — show a Memory's chapters in `MemoryDetail` (decrypted titles), an **Add chapter** affordance,
  open a chapter to its **own** gallery (it renders like a Memory; `browse.ts`/navigation include CHAPTER under
  a Memory), rename/delete; upload into a chapter seals under the chapter's `K_mem`. i18n. · Done when:
  `npm run check` green; a `MemoryDetail` test renders chapters + creates/opens one.
- [x] 4 coverage + verify — new
  [uc.memory.chapters.yaml](../docs/coverage/features/memory/use-cases/uc.memory.chapters.yaml); note the
  chapter-key model on [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml);
  live browser smoke: create a chapter in a Memory, upload an image into it, reopen — it persists, sealed under
  the chapter's `K_mem`. · Done when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Chapters (recursive `K_mem`, create/open/CRUD + media) | Status |
|---|---|---|
| backend / contracts | allow `wrappedContentKey` on CHAPTER; structure rules already enforce nesting | ✅ done |
| web | recursive key model + chapter UI over the ready node/media data layer | ✅ done |
| apple / android | node/media data layer exists; chapter UI + recursive key plumbing are their own CR | ◻ **separate (native)** |

## Definition of done

A user can create chapters inside a Memory, open one to its own gallery, upload media into it, and rename/delete
it — each chapter sealed under its own random `K_mem` wrapped under the parent's content key (recursive model,
operator-blind). Sharing stays out, but the key layout makes per-chapter sharing possible later without a
re-encryption migration. ADR 0012 records the resolved chapter-key decision. `npm run check` +
`./gradlew -p backend check` + the three guards green; the smoke confirmed.

## Retrospective

Shipped in four phases. **Key model + contract:** resolved [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)'s
deferred chapter-key question with an Addendum — **recursive `K_mem`**: every content node (MEMORY = CHAPTER)
carries its own random `K_mem` wrapped under its parent's content key (`K_SC` for a top-level Memory, the parent
Memory's `K_mem` for a chapter), no new key type, folders stay key-less. The contract now allows
`wrappedContentKey` on a `CHAPTER` (wording only — the backend already stored it agnostically, **no Kotlin
change needed**, no migration). **Web data layer:** generalized `tree.ts` to the recursive model — a shared
`createContentNode`, `createChapter` (wraps under the parent's `K_mem`), `openContentKey`, and `nodeContentKey`
which walks the content-key chain from `K_SC` down (folders transparent). **Web UI:** chapters show as a chip
strip in `MemoryDetail` with an icon-only **Add chapter** action; a chapter opens to its own gallery (a CHAPTER
is now an openable node alongside MEMORY); `browse.childrenOf` returns chapters; upload into a chapter seals
under the chapter's `K_mem`.

`./gradlew -p backend check` (incl. the slice round-trip asserting a CHAPTER's own `wrappedContentKey` lists
back verbatim) + `npm run check` (198 web tests: `tree.test` recursive-`K_mem` round-trip + wrong-key rejection
+ `nodeContentKey` chain walk, `browse.test` chapters, `MemoryDetail.test` chapter render + open/create emits)
+ the three trust-chain guards green.

**Live smoke (re-seeded instance, driven in the preview): confirmed end-to-end.** Created chapter "Tag 1"
inside the Smoke Memory (its `K_mem` wrapped under the Memory's `K_mem`) → it appeared as a chip → opened it to
its own empty gallery at `#~me/smoke-memory/tag-1` (the chapter `K_mem` resolved via the chain — upload enabled,
not the unavailable state) → uploaded an image into it (sealed under the **chapter's** `K_mem`) → reload
re-resolved the chain top-down and the image decrypted back ("1 Objekt"). A clean in-app navigation pass
(Memory → chapter → root → Memory) produced **zero** console errors.

**Surfaced along the way:** the first console read showed Vue runtime errors (`invokeDirectiveHook … reading
'value'`, `__vnode` null) — traced to the smoke harness itself (`location.reload()` mid-render + injected DOM
events tearing down the tooltip directive), **not** the app: a marker + pure in-app navigation reproduced none.

**Operator-blindness preserved:** a chapter's `K_mem` travels only as an opaque wrap under its parent's key
(a wrong parent key can't open it — asserted in `tree.test`); the server holds the wrap + node shape and never
inspects it. Every content node is now a **sharing unit by construction** — per-chapter sharing is possible
later without a re-encryption migration.

**Deferred (own CRs):** the aggregated chronological gallery (Memory + all chapters' media in one timeline,
a later CR), rich media metadata + the lightbox EXIF (2-tier `wrappedLayout`/`wrappedExif`, a later CR), sharing
(#19), map/tracks/playlists/journal/buckets, and native (apple/android) chapter UI + recursive key plumbing.
