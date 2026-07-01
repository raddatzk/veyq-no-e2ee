---
id: cr-0062
title: Chapters as a MemoryCard grid (web) — match the design source
status: done
satisfies: [uc.memory.chapters]
adrs: ["0010"]
invariants: [operator-blind-e2ee]
---

## Goal

cr-0060/0061 rendered a Memory's chapters as small text **chips**. The design source (`frontend-noe2ee`
`MemoryDetail` `children` tab) renders them as a **`MemoryCard` grid** — the same tile as Memories in the
browse — plus a "create" card. Bring the Chapters tab in line: cards, not chips.

## Context & constraints

- **Reuse what exists.** veyq already has `MemoryCard` + the global `memory-card-grid` layout
  (`src/styles/memory.css`) used by `MemoryBrowse`. The Chapters tab should render the same
  `memory-card-grid memory-card-grid-memories` with a `MemoryCard` per chapter + the design's `memory-create-card`
  as the *Add chapter* affordance — exactly like the browse Memories section.
- **`MemoryCard` gains an optional `favoritable`.** Chapters aren't favorited from this tab (the design's
  children cards are open-only), so add a `favoritable` prop (default `true`, unchanged for browse) that hides
  the favorite pin when `false`. No favorite wiring for chapters.
- **The header loses its chapter button.** The cr-0060 icon-only *Add chapter* header action is now redundant
  with the in-tab create card (the browse has no header add button either) — remove it; the create card is the
  single add affordance. The gallery Upload action stays in the header.
- **No data/crypto change.** Pure presentation swap of the Chapters panel; `chapters` prop +
  `openChapter`/`createChapter` emits (cr-0060) are unchanged.
- **Scope.** Web only. Deferred: chapter covers/dates on the card (media metadata is a later CR), chapter
  favoriting, drag-reorder (the design's drop targets), native parity.

## Phases

- [x] 1 cards (web) — `MemoryCard` gets `favoritable?: boolean` (default true; pin hidden when false); the
  `MemoryDetail` Chapters tab renders a `memory-card-grid` of `MemoryCard` (favoritable=false, `@open` →
  `openChapter`) + a `memory-create-card` (→ `createChapter`), replacing the chip strip + empty state; remove
  the header's Add-chapter button + the now-unused chip/empty styles + `noChapters` i18n. · Done when:
  `npm run check` green; the `MemoryDetail` test renders chapter cards + the create card and drives
  open/create.
- [x] 2 verify — the coverage note (cr-0061) already covers the Chapters tab; live browser smoke: the Chapters
  tab shows a card per chapter + a create card; a card opens the chapter, the create card adds one. · Done
  when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Chapters as a MemoryCard grid | Status |
|---|---|---|
| web | `MemoryCard` grid + create card in the Chapters tab | ✅ done |
| apple / android | their detail/chapter UI is its own later CR | ◻ **separate (native)** |

## Definition of done

The Chapters tab renders chapters as a `MemoryCard` grid with a create card (like the browse Memories section,
per the design source) instead of text chips; the redundant header add-chapter button is gone. No data/crypto
change. `npm run check` + the three guards green; the smoke confirmed.

## Retrospective

Swapped the Chapters tab from cr-0060/0061's text chips to a **`MemoryCard` grid** matching the design source
(`frontend-noe2ee` `children` tab): the tab now renders `memory-card-grid memory-card-grid-memories` with a
`MemoryCard` per chapter + the design's `memory-create-card` (*Kapitel hinzufügen*) as the add affordance —
identical to the browse Memories section. `MemoryCard` gained an optional `favoritable` (default true; unchanged
for browse) so chapter cards render open-only without a favorite pin. The now-redundant icon-only Add-chapter
header button was removed (the create card is the single add affordance, like the browse); the unused
chip/empty styles + `noChapters` i18n were dropped.

`npm run check` (198 web tests; the `MemoryDetail` test now asserts chapter **cards** — `.memory-card`, no
`.memory-card-pin` — plus the create card driving open/create) + the three guards green.

**Live smoke (re-seeded instance, driven in the preview): confirmed.** The preview server was restarted (fresh
browser), signed back in on 127.0.0.1, opened the Smoke Memory → the Chapters tab renders "Tag 1" as a
MemoryCard (cover icon + title, no pin) beside a dashed "Kapitel hinzufügen" create card — zero chips. Matches
the design source; the interactive open/create flow was already live-verified in cr-0060.

**Deferred:** chapter covers/dates on the card (media metadata is a later CR), chapter favoriting, drag-reorder
(the design's drop targets), native parity.
