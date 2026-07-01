---
id: cr-0067
title: Memory date — sealed wrappedMeta on the node + 4-mode date UI (web/backend)
status: done
satisfies: [uc.memory.date]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Give a Memory a **date** the user can curate — a mode-switch (`auto` / `single` / `range` / `ongoing`) — shown
on its **browse card** (cover + title + range, grid sorted by start date) and in the detail header, all
**operator-blind**. Realizes [uc.memory.date](../docs/features/memory/use-cases/uc.memory.date.md) — it
introduces the node's sealed **`wrappedMeta`** blob (which [uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md) will reuse) and the date UI.

- The effective **day range** `{ mode, from?, to? }` is **materialized** into the node's sealed `wrappedMeta`
  for *every* mode — the browse card grid must show + sort by it **without loading a Memory's media**.
- **`auto` (default)** materializes `min → start`, `max → end` (day-truncated — time ignored) over the Memory's
  own media, counting only **trustworthy** capture times: EXIF-sourced `capturedAt` or a user `capturedAtOverride`
  — **file-modification-date fallbacks are excluded** (an edited photo's file date must not stretch the range).
  Recomputed on media **add/remove**, self-healed on open. **`single` / `range` / `ongoing`** carry the user's
  `YYYY-MM-DD` days.

## Context & constraints

- **Operator-blind (ADR 0012).** A Memory's date is "capture time" one level up from a single media → sealed
  under the node's **`K_mem`** (AAD context `node-meta`), opaque to the server. Stored as one client-versioned
  JSON blob `{ v, date: { mode, from?, to? } }` so the later place slice grows it **without a contract change**.
- **Materialize the range (the reason `auto` is stored, not computed at display).** The browse card grid shows +
  sorts memories by their range but **does not load each Memory's media** — so the effective `{ from, to }` must
  already be sealed on the node. Manual modes store the user's days; **`auto` reconciles the derived min/max from
  the Memory's *complete* media list** at three settle points: (a) **when an upload batch finishes** (once — not
  per file — so both files land in one range; never mid-upload), (b) **on Memory open** (compare stored vs
  actual, correct — the **self-heal**), and (c) after a media delete. Day-truncated: `capturedAt` epoch-ms →
  local `YYYY-MM-DD` (time ignored).
- **Eventual consistency over locking (deliberate, groups/tenant).** Multiple members share a scope, so
  concurrent adds to one Memory are possible now. The `auto` range is a **derived, self-healing** display value:
  a concurrent add can briefly narrow a card's range (blind last-write-wins on `wrappedMeta`), corrected by the
  next **open-time self-heal** — no data loss, no blindness impact. We deliberately **do not** add per-write
  optimistic concurrency (a blind server `media_rev` + compare-and-set) now; it's the documented upgrade if
  strict consistency is ever needed, and a later live-update/poll feature will shrink the window anyway.
- **Folds into the existing node update.** `wrappedMeta` is added to `NodeSummary` (listed), `CreateNodeRequest`
  (optional) and the partial `UpdateNodeRequest` — **no new endpoint** (unlike the media `captured-at` PUT,
  nodes already have `updateNode`). Partial semantics: a non-null `wrappedMeta` updates it, an omitted/null one
  leaves it unchanged (absent `wrappedMeta` = the implicit `auto`-with-no-dated-media state).
- **`auto` reuses the gallery's capture-time tier.** `MemoryDetail` already lists the media with
  `wrappedCapturedAt`; the reconcile unseals + min/max's the effective `capturedAt` on device and re-seals the
  node's `wrappedMeta` if the range changed. Aggregating chapter media is a later slice.
- **Migration** `V17__node_meta.sql` (`veyq_node.wrapped_meta TEXT NULL`). Threaded verbatim through
  `NodeEntity`/store/handler/resource — the blind server stores + echoes ciphertext, never inspects it.
- **Port the date primitives.** `IsoDateField` (PrimeVue DatePicker wrapper, `YYYY-MM-DD`) + the ISO/format
  helpers (`isoDate.ts`, `memoryDateRange.ts`) come from the design source `legacy/frontend-noe2ee`; the 4-mode
  switch realizes the approved mockup (the pre-E2EE design carried only a flat start/end range).
- **Scope.** Web + backend, **date only**. **Deferred (own CRs):** place chips (next slice, same opaque blob),
  Photon autosuggestion + a provider-neutrality ADR, derived place from GPS, aggregated `auto`/timeline across
  chapters, date-on-browse-card (#62), native parity.

## Phases

- [x] 1 contract + backend — add opaque `wrappedMeta` to `NodeSummary` (out), `CreateNodeRequest` +
  `UpdateNodeRequest` (in, partial); migration `V17__node_meta.sql` (`wrapped_meta TEXT NULL`); thread through
  `NodeEntity`/store/handler/resource (create sets it; update sets it when present; list returns it);
  regenerate. · Done when: `./gradlew check` green; a slice test round-trips `wrappedMeta` on create→list and a
  partial `updateNode` (set it; a rename that omits it leaves it unchanged), opaque + never inspected.
- [x] 2 web data — extend the cr-0066 capture-time tier with `capturedAtSource: 'exif' | 'file'` (set at upload
  in `readExif`/`sealMetadata`; **no contract change** — the tier is opaque/versioned); `TreeNode` gains
  `wrappedMeta`; a `MemoryDate` shape `{ mode, from?, to? }` + seal/unseal under the node's `K_mem` (context
  `node-meta`); `unsealMemoryDate(nodeKey, node)`, `setMemoryDate(scopeId, nodeId, K_mem, date)` (re-seal + PATCH
  `updateNode`), and `computeAutoRange(mediaItems, K_mem)` — min/max (day-truncated) over only **trustworthy**
  media (`capturedAtOverride` set, or `capturedAtSource === 'exif'`); port `isoDate`/`memoryDateRange` helpers.
  · Done when: `npm run check` green; a test round-trips each mode, computes an auto range that **excludes
  file-fallback media**, and tolerates absent/malformed (→ empty auto).
- [x] 3 web detail header + auto-reconcile — `MemoryDetail` header shows the effective date (sealed range,
  locale-formatted; a neutral "no date yet"); an editor with the **mode switch** + the fitting `IsoDateField`
  inputs (none/one/two/one), save re-seals + PATCHes + re-renders + emits the updated node so the tree updates.
  When mode is `auto`, **reconcile from the full media list** on Memory open (compare stored vs actual → correct,
  the self-heal) + after an upload batch finishes + after a media delete — re-seal `wrappedMeta` only when the
  range changed. i18n EN+DE. · Done when: `npm run check` green; a `MemoryDetail` test renders + edits each mode,
  materializes the auto range after a seeded upload, and self-heals a stale stored range on open.
- [x] 4 web browse card + sort — `MemoryCard` shows the sealed range (under cover + title); the memory card grid
  (browse + chapters) **sorts by start date** (newest first, undated last), reading each node's `wrappedMeta`
  under its `K_mem`. · Done when: `npm run check` green; a test renders a card's range and orders a mixed grid
  by start date.
- [x] 5 coverage + verify — add
  [uc.memory.date.yaml](../docs/coverage/features/memory/use-cases/uc.memory.date.yaml); live browser
  smoke: upload dated media → the Memory's `auto` range materializes + the card shows it + the grid sorts by it;
  switch to `single`/`range`/`ongoing` and it persists + re-renders; `wrapped_meta` sealed (no plaintext date).
  · Done when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Sealed Memory date (wrappedMeta + 4-mode UI) | Status |
|---|---|---|
| backend / contracts | `wrappedMeta` on the node (opaque) via `updateNode` | ✅ done |
| web | seal date under `K_mem`, 4-mode header UI, materialized `auto`, card range + sort | ✅ done (live smoke pending) |
| apple / android | data layer can carry `wrappedMeta`; native date UI is their own CR | ◻ **separate (native)** |

## Definition of done

A Memory shows a curatable date — `auto` materialized from its media (recomputed on add/remove), or a manual
single/range/ongoing — sealed under `K_mem` in an opaque `wrappedMeta` on the node; the browse card shows the
range and the card grid sorts by start date, all without loading a Memory's media; the server holds only
ciphertext. `npm run check` + `./gradlew -p backend check` + the three guards green; the smoke confirmed. The
place slice reuses the same blob.

## Retrospective

Delivered all five phases; `./gradlew -p backend check` + `npm run check` (web: **241 passing + 1 skipped** across 41 files) +
the three trust-chain guards all green; the live browser smoke confirmed against a V17-migrated instance.

**What shipped.** One opaque, client-versioned `wrappedMeta` blob on the node (`{v, date{mode,from?,to?}}`),
sealed under `K_mem` (AAD context `node-meta`), folded into the existing `createNode` / `updateNode` (partial) /
`listNodes` — no new endpoint, migration `V17__node_meta.sql`. Web: the cr-0066 capture-time tier grew a
`capturedAtSource: 'exif' | 'file'` (set in `sealMetadata`, opaque/versioned — no contract change); a
`memoryDate.ts` data layer (seal/unseal, `computeAutoRange` over *trustworthy* media only, `setMemoryDate`,
`sortNodesByDate`); a 4-mode header editor (`MemoryDateEditor.vue` + the ported `IsoDateField.vue`); the
materialized `auto` range self-healing on open + after an upload batch; and the browse card range + date-sort.
i18n EN+DE.

**Decisions that held.** (1) *Materialize, don't compute-at-display* — the browse card grid unseals each node's
`wrappedMeta` (no media load); the live smoke proved the card + sort work with the media never fetched. (2)
*Trustworthy-only `auto`* — `capturedAtSource` cleanly excludes file-date fallbacks; a canvas JPEG (no EXIF)
correctly yields an empty `auto`, while a real dated media materialized `01.01.2010` live. (3) *Eventual
consistency* — the open-time reconcile is the self-heal; the smoke caught it firing live (opening "Sort Smoke"
sealed a brand-new `wrapped_meta` row from its dated media).

**Notes / deviations.** The `setMemoryDate` signature seals only `{v, date}` — the **place slice** will need to
unseal-merge the existing blob before re-sealing so it doesn't clobber `place` (flagged in-code). The
`IsoDateField` port also brought over the design source's `primevueLocale` table (`applyPrimeVueLocale` merges
the calendar strings into the reactive global PrimeVue config on locale change), so the DatePicker **panel**
(month/day names, Monday-first for DE) is localized too — live-verified showing "Januar 2026". The
**media-delete** reconcile settle point has no delete surface yet; the
open-time self-heal covers it once media-delete lands. `wrappedMeta` `maxLength` set to 8192 to leave room for
the place array. Live smoke verified operator-blindness directly in MariaDB: both nodes' `wrapped_meta` are AEAD
ciphertext (version byte `02` + nonce), containing no plaintext day in any format.

**Deferred (unchanged):** place chips (next slice, same blob), Photon autosuggestion + provider ADR, derived
place from GPS, aggregated `auto`/timeline across chapters, native (apple/android) date UI.
