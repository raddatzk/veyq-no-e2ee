# UC: Memory cover — a chosen, cropped cover shown everywhere

- **ID:** uc.memory.cover
- **Primary actor:** A member with an **unlocked vault** who has a Memory open and wants to give it a **cover**.
- **Goal:** Curate a Memory's **cover** — pick **one image** and **crop** it — **sealed under the Memory's `K_mem`**
  and shown identically wherever the Memory appears: its **browse card**, its **detail header**, and its **map pin**
  ([uc.memory.place](uc.memory.place.md)). **Operator-blind** — the server never sees which image, or the crop.
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_mem`** is held). The node already carries
  the sealed `wrappedMeta` blob ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); this adds a `cover`
  sub-object beside the `date`/`location`.
- **Postconditions (success):** The Memory shows its chosen cover on every surface; the user can re-crop, change, or
  remove it. The server stored only the **opaque `wrappedMeta`** blob + the unchanged node shape.

## What a cover is

A cover is **one image plus a crop**, deliberately chosen — **there is no auto-cover** (a cover never appears from
nothing; unset ⇒ a placeholder shows, like a location is never auto-created). The image source is:

- **an existing Memory media** — referenced by `mediaId` (this use-case's first slice, cr-0072); or
- **a brand-new uploaded image** that is **not** added to the Memory's media — a **standalone cover blob** (a later
  slice / CR: it needs a blob committed to the node with no `veyq_media` row — contract + backend).

The **crop is normalized `{ x, y, width, height }` in 0..1** against the source image (the pre-E2EE design source's
`CoverCrop`), so **one stored crop renders identically at any size** — a small card, a larger detail header, or a
map pin — with no per-surface crop and no cropped-image upload. The whole `cover { source, crop }` is sealed in the
node's `wrappedMeta` under `K_mem`; the display just expands the decrypted thumbnail inside an `overflow:hidden` box
and offsets it.

## Main flow

1. With a Memory **open**, the header offers **"set cover"**. It opens a picker over the Memory's **decrypted
   media** (the tiers the gallery already loads); the user picks one.
2. A **16:9 crop viewport** shows the picked image; the user **drag-pans** and **zooms** (1–3×) to frame it. Save
   normalizes the pan/zoom back to the `0..1` crop rectangle.
3. On save, the client seals `cover { mediaId, crop }` into the node's `wrappedMeta` under `K_mem` (read-modify-write
   — the sibling date/location are preserved) and PATCHes it via `updateNode`. The detail header re-renders; the
   browse card and the map pin pick it up.
4. **Everywhere the Memory shows**, its cover is rendered from the same sealed crop over the chosen media's
   decrypted thumbnail: the **browse card**, the **detail header**, the **map pin**. **Remove** clears the cover →
   the placeholder returns on every surface.

## Alternate / exception flows

- **E1 — no cover set:** every surface shows the **placeholder** (the soft-accent images tile). Deliberate — no
  auto-pick.
- **E2 — the chosen media was deleted:** the `cover.mediaId` dangles → best-effort fall back to the placeholder
  (never a broken card/pin); the user can pick a new cover.
- **E3 — vault locked / Memory not open:** without the node's `K_mem` the `wrappedMeta` can't be unsealed → the
  cover is unavailable until unlock + open.
- **E4 — malformed / wrong-key `wrappedMeta` or crop:** best-effort — a bad crop normalizes to the full frame; an
  unopenable blob shows the placeholder. Never a throw.

## Quality notes

- **Operator-blind.** The server holds the node shape + the **opaque `wrappedMeta`**; never the chosen image id or
  the crop. The cover is sealed under `K_mem` and unsealed only on device. (Envelope + node-level blindness:
  [en.memory.node-meta](../enablers/en.memory.node-meta.md).)
- **One normalized crop, one image, everywhere.** A single `0..1` crop drives the card, the detail header, and the
  map pin through one shared display formula — a Memory reads as itself across every surface, with no re-cropping
  and no extra blob for the existing-media case.
- **No auto-cover (deliberate).** A cover is a curation choice, mirroring the location: it is set on purpose, and
  its absence is an honest placeholder rather than a guessed photo. (This retires the cr-0071 stopgap where the map
  pin auto-picked the media nearest the place.)
- **Reuse the existing UI/UX — never invent.** The picker + hand-rolled pan/zoom crop + the crop-display formula are
  ported from the design source (`legacy/frontend-noe2ee` — `MemoryCoverDialog` / `MemoryCoverImage` / `CoverCrop`);
  only the data layer is swapped to the contracts client + `K_mem` sealing. No crop library is added.
- **Web first, then native parity** ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)).

## What this means for you / Was das für dich bedeutet

**EN —** Give a memory a **face**. Pick one of its photos, crop it how you like, and that cover shows up everywhere
the memory appears — its card, its page, and its pin on the map. Which photo you chose and how you framed it are
locked with the memory's own key on your device; the server only ever stores scrambled data.

**DE —** Gib einer Erinnerung ein **Gesicht**. Wähle eines ihrer Fotos, schneide es zu, wie du magst — und dieses
Cover erscheint überall, wo die Erinnerung auftaucht: auf ihrer Karte, ihrer Seite und ihrem Pin auf der Landkarte.
Welches Foto du gewählt und wie du es zugeschnitten hast, wird mit dem eigenen Schlüssel der Erinnerung auf deinem
Gerät verschlüsselt; der Server speichert nur verschlüsselte Daten.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Use-cases: [uc.memory.date](uc.memory.date.md) + [uc.memory.place](uc.memory.place.md) (the sibling curated
  sub-objects on the same `wrappedMeta`), [uc.memory.gallery](uc.memory.gallery.md) (the media tiers the cover is
  chosen from + displayed with), [uc.memory.browse](uc.memory.browse.md) (the card + `updateNode` this folds into)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_mem seals node content),
  [ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md) (web first; native parity)
- Future: a cover from a **new upload** (a standalone cover blob — contract + backend, cr-0073); native cover parity.
  cr-0073 generalizes the cr-0072 **`mediaId`-only** `cover` shape into a discriminated **`source` (media | standalone
  blob)** — with a backward-compatible `normalizeMemoryCover` so existing `{ mediaId, crop }` seals still open — and
  branches `loadNodeCover` onto a blob-fetch path; the display (`MemoryCoverImage` + `coverImageStyle`) and the crop
  editor are reused unchanged.
