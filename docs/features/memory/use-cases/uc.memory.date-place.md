# UC: Memory date + place — sealed, curated metadata

- **ID:** uc.memory.date-place
- **Primary actor:** A member with an **unlocked vault** who has a Memory (or chapter) open and wants to give it
  a **date** and a **place**.
- **Goal:** Curate a Memory's **date** (a mode-switch: `auto` / `single` / `range` / `ongoing`) and its **place**
  (an ordered list of free-text stops), both **sealed under the Memory's `K_mem`** and shown on the Memory's
  **browse card** (cover + title + range) and its detail header — **operator-blind** (the server never sees a
  day or a place name).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_mem`** is held). The node model
  already carries per-node sealed fields (`encryptedName`, `wrappedContentKey`); this adds one more,
  `wrappedMeta`.
- **Postconditions (success):** The Memory shows its effective date (and later its place); the user can switch
  the date mode and enter manual values, or add/reorder/remove place stops. The server stored only an **opaque
  `wrappedMeta` blob** + the unchanged node shape.

## The model — one sealed `wrappedMeta` on the node ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md))

ADR 0012 already earmarks **"capture time, location"** as `K_mem` content. A Memory's curated date + place are
exactly that, one level up from a single media: they belong to the **node**, travel with a shared Memory, and
must be opaque to the operator.

- **`wrappedMeta`** — a new optional, opaque base64 field on a content node (MEMORY/CHAPTER), sealed under that
  node's **`K_mem`** (AAD context `node-meta`). It carries a **client-only, versioned** JSON blob — like the
  media EXIF tier, its shape can grow without a contract change (the operator never learns a field):

  ```
  { v: 1,
    date?:  { mode: 'auto' | 'single' | 'range' | 'ongoing',
              from?: 'YYYY-MM-DD',   // start day (single ⇒ from == to; ongoing ⇒ no `to`)
              to?:   'YYYY-MM-DD' }, // end day (absent for ongoing / undated auto)
    place?: [ { name: string, lat?: number, lng?: number }, … ]   // ordered stops (added by a later slice)
  }
  ```

- **Set/updated in place** through the existing partial `updateNode` (`PATCH …/nodes/{nodeId}`) — no new
  endpoint; a rename/move that omits `wrappedMeta` leaves it unchanged. Also settable at `createNode`. Returned
  on `listNodes` (small) — **this is what lets a browse card show the range without loading the Memory's media**.

- **Materialize the effective range — don't recompute at display.** `wrappedMeta.date` stores the actual
  `{ mode, from?, to? }` day range for **every** mode (`single` ⇒ `from == to`; `range` ⇒ both; `ongoing` ⇒
  `from`, no `to`). The **card grid can't compute anything** — the Memory's media isn't loaded there — so the
  range must already be sealed on the node.

- **`auto` counts only *trustworthy* capture times.** A media's `capturedAt` (cr-0066) is the EXIF
  `DateTimeOriginal` **or**, absent that, a **file-modification-date fallback**. A file date is not a real
  capture time — an edited photo whose EXIF was dropped would carry its *edit* date and wrongly stretch the
  range — so the capture-time tier now records `capturedAtSource: 'exif' | 'file'`, and **`auto` includes a
  media only when its `capturedAt` is EXIF-sourced *or* the user set a `capturedAtOverride`** (an intentional
  correction). File-fallback media (and legacy media with no recorded source) are excluded from `auto`; a Memory
  with only such media has an empty `auto` range (set it manually). (The media grid still *sorts* by the
  effective `capturedAt` regardless of source — cr-0066 unchanged.)

- **`auto` derives from the Memory's own media, materialized by a full-list reconcile.** The range is
  `min → start`, `max → end` (day-truncated, time ignored) over the **trustworthy** `capturedAt` of the Memory's
  media — the always-listed capture-time tier from [uc.memory.gallery](uc.memory.gallery.md) (cr-0064/0066). The
  reconcile recomputes from the **complete** media list and re-seals `wrappedMeta` **only if the range changed**,
  at three settle points: (a) **when an upload batch finishes** (once, not per file — so both files of a batch
  are included), (b) **when the Memory is opened** (compare the stored range to the actual and correct — the
  **self-heal**), and (c) after a media delete. (Aggregating a Memory *plus its chapters'* media is a later
  slice.)

- **Place default = free-text, no leak.** A stop is a **name only** by default; optional `lat/lng` come only
  from opt-in autosuggestion (a later slice, behind a vendor-neutral geocoding-provider seam — see *Out*).

## Main flow (date)

1. **In the browse grid**, each Memory **card** shows its sealed range (cover + title + range), and the grid is
   **sorted by start date**. The range is read from the node's `wrappedMeta` (unsealed under that node's
   `K_mem`) — no media load.
2. With a Memory **open**, the header shows the same **effective date**, and the user **edits** it → a **mode
   switch** (`auto` / `single` / `range` / `ongoing`) reveals the fitting inputs: none for `auto`; one day
   picker for `single`; from/to for `range`; a start for `ongoing`.
3. On save, the client seals `{ v, date }` under the Memory's **`K_mem`** and PATCHes `wrappedMeta`; the header
   (and, next browse, the card) re-render. Choosing `auto` re-materializes the range from the current media.
4. **When media is added or removed** from an `auto` Memory, the client recomputes min/max over its media and
   re-seals `wrappedMeta` so the card stays correct (self-healed on the next open as a safety net).

## Main flow (place — a later slice)

1. The header shows the ordered **place chips** (stop names). The user adds a stop (free-text), reorders, or
   removes one; the client re-seals `wrappedMeta` (adding the `place` array to the same blob) and PATCHes it.

## Scope — sliced

- **Slice 1 — date (this CR):** the `wrappedMeta` plumbing (contract field on create/update/summary, migration,
  backend threading, web seal/unseal under `K_mem`); **the 4-mode date editor** in the Memory detail header;
  **`auto` materialized** from the Memory's own media on add/remove/open; and the **browse card** showing the
  range + the card grid **sorted by start date**. i18n EN+DE.
- **Slice 2 — place chips (next CR):** free-text ordered stops added to the **same** opaque `wrappedMeta` blob —
  **no contract change** (the blob is client-versioned); web edit UI (chips + input).
- **Out (own CRs / later):**
  - **Place autosuggestion (coordinates)** — opt-in, behind an **abstract geocoding-provider interface** (veyq
    binds to no vendor; reference provider **Photon**, OSS/OSM, `lang` mapped to the locale with a `default`
    fallback). Carries a transparency note ("suggestions use a map service; your input leaves your device"); a
    provider-neutrality **ADR** lands with it. Commercial geocoders (Google/Mapbox/HERE) rejected (lock-in +
    leak).
  - **Derived place** — stops inferred from media GPS clusters (needs reverse-geocoding for names → the same
    leak, so opt-in).
  - **Aggregated `auto` date/timeline** — a Memory *plus its chapters'* media in one chronological range/gallery.
  - **Date on the browse card** ([#62]) — unseal each node's `wrappedMeta` in the card grid.
  - **Native (apple/android)** date/place UI — web first ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)); the data layer carries `wrappedMeta` after regen.

## Alternate / exception flows

- **E1 — no dated media in `auto`:** the header shows a neutral "no date yet" state; adding dated media fills it.
- **E2 — vault locked / Memory not open:** without the node's `K_mem` the `wrappedMeta` can't be unsealed → the
  date/place are unavailable until unlock + open.
- **E3 — malformed / wrong-key `wrappedMeta`:** best-effort — treated as no manual date (falls back to `auto`),
  never a broken header.
- **E4 — invalid manual range (`to` < `from`):** rejected in the editor before sealing.
- **E5 — concurrent members change media (groups/tenant):** the materialized `auto` range is a **derived,
  self-healing** aggregate. Two members adding media to the same Memory at once can leave a card's range
  transiently too narrow (a blind last-write-wins on `wrappedMeta`); the **self-heal reconcile on the next open**
  (E-flow above) recomputes from the now-complete media list and corrects it. No data loss — the media stays
  correctly sealed; only the cached display range lags. (A later live-update/poll feature will surface others'
  new media into open grids and shrink the window further.)

## Quality notes

- **Operator-blind.** The server sees the node shape + coarse timestamps + the **opaque `wrappedMeta`** blob;
  never a day, a place name, or a coordinate. GPS-derived data never leaves the device except through the opt-in
  geocoding path (later slice), which is explicitly flagged.
- **One extensible blob, not many fields.** Date now, place next, future metadata later — all in the same
  client-versioned `wrappedMeta`, so the contract + schema don't churn per addition (a lesson from the
  media-metadata tiers).
- **`auto` reuses the capture-time tier** the gallery already lists (cr-0064/0066); it is materialized into
  `wrappedMeta` by a full-list reconcile at settle points (a small extra `updateNode` PATCH) so the browse grid
  can show + sort by the range without ever loading a Memory's media.
- **Eventual consistency over locking (deliberate).** The `auto` range is a derived, self-healing display value.
  Rather than add per-write optimistic concurrency (a blind server `media_rev` + compare-and-set), we accept that
  a concurrent-member add can briefly narrow a card's range and let the **open-time self-heal** correct it — no
  data loss, no operator-blindness impact. Materialize the range only **after an upload finishes**, never
  mid-upload. (If strict consistency is ever needed, the `media_rev` CAS is the blind-safe upgrade.)
- **Reuse the existing UI/UX — never invent.** The date picker + ISO formatting are ported from the design
  source (`legacy/frontend-noe2ee` `IsoDateField` / `memoryDateRange`); the 4-mode switch realizes the approved
  mockup (the pre-E2EE design carried only a flat start/end range).
- **Web first, then native parity** ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)).

## What this means for you / Was das für dich bedeutet

**EN —** Give a memory a **when** and a **where**. The date can just **figure itself out** from your photos
(earliest to latest), or you can set a single day, a span, or an open-ended "since…". The place is a simple list
of stops you type in — a trip can have several. It's all locked with the memory's own key on your device, so the
server only ever stores scrambled data — it never learns the day you were there or the name of the place.

**DE —** Gib einer Erinnerung ein **Wann** und ein **Wo**. Das Datum kann sich **automatisch** aus deinen Fotos
ergeben (frühestes bis spätestes), oder du setzt einen einzelnen Tag, einen Zeitraum oder ein offenes „seit…".
Der Ort ist eine einfache Liste von Stationen, die du eintippst — eine Reise kann mehrere haben. Alles wird mit
dem eigenen Schlüssel der Erinnerung auf deinem Gerät verschlüsselt; der Server speichert nur verschlüsselte
Daten und erfährt weder den Tag noch den Namen des Ortes.

## Related

- Feature: [Memory](../_index.md)
- Use-cases: [uc.memory.browse](uc.memory.browse.md) (the node CRUD + `updateNode` this folds into),
  [uc.memory.gallery](uc.memory.gallery.md) (the capture-time tier that `auto` derives from),
  [uc.memory.chapters](uc.memory.chapters.md) (a chapter is a node too — it carries `wrappedMeta` the same way)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_mem seals "capture time, location"
  — this UC applies it at the node level), [ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md) (web first; native parity)
- Future: place **autosuggestion** (Photon, behind a provider seam — its own ADR) + **derived** place from GPS;
  the **aggregated** chronological date/gallery across chapters
