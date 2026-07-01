# UC: Memory place — one sealed, named location

- **ID:** uc.memory.place
- **Primary actor:** A member with an **unlocked vault** who has a Memory (or chapter) open and wants to give it
  a **place**.
- **Goal:** Curate a Memory's **place** — **one named location**: a map position derived from the Memory's own
  photo GPS, plus a name that is either typed or reverse-geocoded — **sealed under the Memory's `K_mem`** and
  shown on the Memory's detail header and a later **tenant-wide map** — **operator-blind** (the veyq server never
  sees a coordinate or a place name).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_mem`** is held). The node's
  `wrappedMeta` blob already exists (shipped for the date, [uc.memory.date](uc.memory.date.md)); place adds a
  `location` sub-object to it.
- **Postconditions (success):** Once set, the Memory shows its named location; the user can "set location" (a
  medoid position + a typed or reverse-geocoded name), move/rename it, and see it as a pin on the tenant map. The
  server stored only the **opaque `wrappedMeta` blob** + the unchanged node shape.

## The sealed place — the node's `location`

Place reuses the **same** node-level `wrappedMeta` blob the date introduced — the **envelope** (sealing under
`K_mem`, versioning, `updateNode` / `listNodes` threading, and *why it is materialized on the node so the map
reads it without loading media*) is the enabler
[en.memory.node-meta](../enablers/en.memory.node-meta.md); adding `location` needs **no contract change**. This
use-case defines the `location` sub-object and its behavior:

```
location?: {                        // ONE named place; only ever materialized WITH a name
  name:         string,             // the label — mandatory (there is no empty-name / unnamed-pin state)
  nameSource:   'geocoded' | 'manual',   // manual = frozen; geocoded = may refresh when the anchor moves
  derived?:     { lat, lng },        // map position = the MEDOID of the media GPS (never the arithmetic mean)
  derivedMode?: 'fixed' | 'auto',    // user's choice at set-time: snapshot vs. re-medoid as media are added
  nameAnchor?:  { lat, lng, zoom } } // coord + zoom a geocoded name was resolved at (Atlantik→Kanaren→Teneriffa)
```

A Memory carries **one location**: a map position plus a name. The two are **independent fields with independent
provenance**, and setup is **deliberate** — the opposite of the date's silent `auto`.

- **Deliberate setup, then optional auto-fill.** A location never *appears from nothing*: the user makes a
  deliberate choice to have one, and a **name is mandatory** (no empty-name / unnamed-pin state — no name ⇒ no
  location). But the *filling* can be deferred: setup can happen **on the Memory open** (from media already
  present) **or already at Memory creation** — same as the date is offered up front. Set up at creation there are
  no media yet, so you give a **fallback name** and `derivedMode` **must be `auto`** (nothing to snapshot);
  `derived` (and, under opt-in, the geocoded name) then fill from the **first geotagged media**. The deliberate
  act is the *setup + the opt-in*; the fill is what follows.
- **The fallback name is a placeholder under `geocoded`, not a frozen `manual` name.** With `nameSource:
  'geocoded'` the typed fallback is the display value **until** the reverse lookup resolves a real name and
  replaces it; if no geotagged media ever arrive (or geocoding stays off), the fallback simply persists — so a
  name exists from creation and it degrades **blind**. A `manual` fallback, by contrast, is the final name,
  frozen, never geocoded.
- **Position = the MEDOID of the media GPS**, not the arithmetic mean. The medoid is the *actual* photo location
  nearest the centre of the set — so the marker (a) **never lands in the sea** (the mean of scattered points can),
  and (b) is **outlier-robust + stable** (a single stray photo, or a few new ones, barely move it). `derivedMode`
  is the user's choice at set-time: `fixed` (a snapshot) or `auto` (re-medoid as media are added — and it stays
  put *because* it's a medoid).
- **Name and coordinate are independent.** `name` is either **typed by hand** (`nameSource: 'manual'`, e.g. "at
  grandma's") **or reverse-geocoded** from `nameAnchor` (`'geocoded'`). `nameSource` decides whether it may
  auto-refresh when the anchor moves; a manual name is frozen and always wins.
- **`nameAnchor` = coordinate + zoom.** The **zoom picks the name's granularity** — the same coordinate resolves
  to `Atlantischer Ozean` (zoomed out) → `Kanaren` (region) → `Teneriffa` (island). This is the standard
  reverse-geocoding model (Nominatim's `zoom`). On the map you frame an extent; its centre + zoom is the anchor.
- **Reverse-only geocoding, opt-in, blind to *veyq*.** The coordinate always comes first (photos or a set marker);
  the name follows via a **reverse** lookup. veyq never does forward autocomplete. The lookup is **opt-in**, goes
  **client-direct to the provider** (never through the veyq backend — that is what keeps the *veyq operator*
  blind), carries **no veyq identity** (a bare coordinate), and is behind a **provider-neutral seam**
  (`reverse(lat, lng, zoom, lang) → name`; reference **Nominatim**, operator-configurable, off-until-configured —
  see [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md)).
- **When the request fires — opt-in is standing consent.** With the lookup enabled it runs either on an
  **explicit action** (you frame an extent on the map → name at your chosen zoom) **or automatically when the
  first geotagged media arrives** for a `geocoded` `auto` location (forming `nameAnchor` at a **default zoom** —
  refine it later on the map). It **never** fires without the user's opt-in; enabling auto-geocode *is* the
  consent to those automatic reverse lookups. A member who hasn't opted in makes **no request at all**.
- **On the map ⟺ a coordinate; a location ⟺ a name.** These are two different conditions: `name + derived` ⇒ a
  labelled pin; `name` only (no photo GPS) ⇒ shown in the detail as text, **not** on the map; GPS without a name
  ⇒ doesn't exist (you name it when setting).
- **The tenant-wide map reuses `listNodes` — there is no "locations API".** A locations endpoint would hand the
  operator the coordinates. Instead the map reads the **same opaque `wrappedMeta`** `listNodes` already returns,
  unseals each `location` under its `K_mem`, and plots client-side. No server-side geo query (the blind server
  can't filter by a coordinate it never sees); the client fetches all and plots what it can open. (Map tiles are
  their own opt-in/self-host concern — the tile server sees the viewport — handled by the map slice.)

## Main flow

**Set up with media present (Memory open).** The user triggers **"set location"**; the client forms `derived` as
the **medoid** of the Memory's media GPS (from the media's already-sealed EXIF) and asks for a **name** — either
**typed** (`manual`, e.g. "at grandma's") *or*, if the reverse lookup is enabled, **framed on the map** (its
centre + zoom = `nameAnchor` → the provider returns a name at that resolution, `geocoded`, still editable). On
save the client seals `{ v, …, location }` under `K_mem` and PATCHes `wrappedMeta`.

**Pre-arm at creation (no media yet).** Just as the date is offered up front, the user can declare a location when
creating the Memory: a **fallback name** (mandatory) + `nameSource` (`manual` = final, or `geocoded` = placeholder
to be resolved) with `derivedMode: 'auto'` (forced — nothing to snapshot). Then **on the first geotagged media**,
`derived` forms as the medoid and — if the user opted into geocoding — `nameAnchor` forms at a default zoom and
the name resolves, replacing the fallback. No opt-in ⇒ the fallback simply stands (blind).

**After set-up.** `derivedMode: 'auto'` re-medoids `derived` as media are added (it barely moves); a `manual` name
and a `fixed` position are frozen; a `geocoded` name may refresh when its anchor moves.

**The map.** A **toggle in the tenant root** shows all Memories as pins, read from each node's sealed `location`
via `listNodes` (no media load, no locations endpoint). Clicking a pin opens the Memory.

**Sharing (groups/tenant).** The resolved name is stored **sealed in the shared `wrappedMeta`**, so any member
holding `K_mem` sees it **without their own device contacting the provider**. The opt-in governs whether *your*
device makes a request — not whether you may see a name someone else resolved. One member's opt-in benefits the
group; if nobody opts in, only manually-typed names exist.

**Multilingual names.** A stored `name` is a **single frozen string** in the resolver's language — we do **not**
keep it translated across locale switches (the server can't, and it would mean a fresh request). Because
`nameAnchor` is kept, an opted-in viewer can re-resolve the name in **their** language on demand. Nominatim
returns names from OSM `name:<lang>` tags via `accept-language` with **graceful fallback to the local name** (no
hard error on an unsupported language) — so de+en are well covered and further locales degrade, never break.

## Scope — sliced

- **Slice 1 — set location (first place CR):** the `location` model (`derived` medoid / `derivedMode` /
  `nameAnchor` / `name` / `nameSource`) in the **same** opaque `wrappedMeta` blob — **no contract change** (the
  blob is client-versioned); the **"set location"** action (medoid from the media's existing GPS + a mandatory
  name, typed) in the detail header, plus **pre-arm at creation**. Blind, no external call, immediately visible.
- **Slice 2 — the tenant map:** a toggle in the tenant root plotting all Memories from their sealed `location`
  (via `listNodes`, no media load); pin → open; plus a **tile-provider seam** (opt-in/self-host — the tile server
  sees the viewport).
- **Slice 3 — reverse-geocoding:** the opt-in `reverse(lat,lng,zoom,lang) → name` behind the provider-neutral
  seam (reference **Nominatim**, operator-configurable, off-until-configured); the map's frame-an-extent → name
  UX; the standing-consent auto-geocode on the first geotagged media; the provider-neutrality **ADR**
  ([ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md)) lands with it. Commercial geocoders
  (Google/Mapbox/HERE) rejected (lock-in + leak).
- **Out (own CRs / later):**
  - **Always-listed geo-tier** — an optimization moving media GPS out of the lazy `wrappedExif` into an
    always-listed tier (a cr-0066-style move), so "set location" and a per-photo map don't lazy-load. Deferred:
    the deliberate "set location" reads GPS on demand, so it's an optimization, **not** a prerequisite.
  - **On-device coarse names** — a bundled place dataset resolving `lat/lng → country/region/city` **blind**
    (no request), as a leak-free alternative to the external reverse-lookup for coarse labels; multilingual cost
    (per-locale data), so deferred behind the provider seam.
  - **Native (apple/android)** place/map UI — web first ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)); the data layer carries `wrappedMeta` after regen. (Note: the OS geocoders — `CLGeocoder`, Android `Geocoder` — call home, so native "blind" naming means our bundled dataset, not the platform API.)

## Alternate / exception flows

- **E1 — vault locked / Memory not open:** without the node's `K_mem` the `wrappedMeta` can't be unsealed → the
  place is unavailable until unlock + open.
- **E2 — malformed / wrong-key `wrappedMeta`:** best-effort — treated as no location; never a broken header/pin.
- **E3 — "set location" with no photo GPS:** `derived` can't be formed. The user still types a **name**
  (`manual`) → a valid **name-only** location: shown in the detail, but **not** on the map (no coordinate).
- **E4 — reverse-lookup off or unreachable:** the name field falls back to **manual typing** — no external
  request, fully blind. The map/pin still works from `derived`; only the auto-name is unavailable.
- **E5 — a member who declined geocoding opens a shared Memory:** they **see** the stored (sealed) name that
  another member resolved, with **no request from their device** — the opt-in is per-device-request, not
  per-view.
- **E6 — invalid manual range / bad input:** the editor validates before sealing (e.g. a name is required to
  save a location).

## Quality notes

- **Operator-blind — the *veyq* operator is the adversary.** The veyq server sees the node shape + coarse
  timestamps + the **opaque `wrappedMeta`** blob; never a place name or a coordinate. The **opt-in
  reverse-geocoding request is a different, consented trust boundary**: it goes **client-direct** to the provider
  (never through the veyq backend — proxying would let the operator see the coordinate), carries **no veyq
  identity** (a bare coordinate), and is transparent. The threat-model clarification + this third-party boundary
  live in [security-model](../../../crosscutting/security-model.md); the provider seam in
  [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md).
- **Place is deliberately set up, then may auto-fill; the medoid keeps it honest.** A location never *appears
  from nothing* — the user deliberately sets one up (at open or at creation) with a mandatory name. From there it
  may auto-fill: the position is the **medoid** of the media GPS (an actual photo spot, never the sea, barely
  moving as media are added — this is what makes `derivedMode: 'auto'` safe), and, **only under the user's opt-in**,
  the `geocoded` name auto-resolves on the first geotagged media (standing consent — see the reverse-lookup note).
- **Web first, then native parity** ([ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md)).

## What this means for you / Was das für dich bedeutet

**EN —** Give a memory a **where**. You pick "set location": your photos' own GPS drop a pin at a real spot you
visited, and you give it a name — type one ("at grandma's"), or, if you switch it on, let a map service turn the
pin into a name ("Tenerife"). Later, a map shows all your memories as pins. It's all locked with the memory's own
key on your device, so **veyq's server** never learns the place or the coordinates. The only thing that ever
leaves your device is — if *you* turn it on — a bare coordinate sent straight to the map service to fetch a name;
veyq never sees it.

**DE —** Gib einer Erinnerung ein **Wo**. Du wählst „Ort festlegen": das GPS deiner Fotos setzt einen Pin an einen
echten Ort, an dem du warst, und du gibst ihm einen Namen — tippst ihn selbst („bei Oma") oder lässt, wenn du es
einschaltest, einen Kartendienst aus dem Pin einen Namen machen („Teneriffa"). Später zeigt eine Karte alle deine
Erinnerungen als Pins. Alles wird mit dem eigenen Schlüssel der Erinnerung auf deinem Gerät verschlüsselt;
**veyqs Server** erfährt weder den Ort noch die Koordinaten. Das Einzige, was je dein Gerät verlässt, ist —
*wenn du es einschaltest* — eine nackte Koordinate, direkt an den Kartendienst geschickt, um einen Namen zu holen;
veyq sieht sie nie.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Use-cases: [uc.memory.date](uc.memory.date.md) (introduced the shared `wrappedMeta` blob this reuses),
  [uc.memory.browse](uc.memory.browse.md) (the node CRUD + `updateNode` this folds into),
  [uc.memory.gallery](uc.memory.gallery.md) (the media EXIF/GPS tier the medoid derives from)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_mem seals "capture time, location"
  — this UC applies it at the node level), [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md)
  (reverse-only, provider-neutral geocoding; Nominatim reference; veyq-blind), [ADR 0010](../../../decisions/0010-native-clients-shared-contracts-not-code.md) (web first; native parity)
- Crosscutting: [security-model](../../../crosscutting/security-model.md) (operator-blind = the veyq operator;
  third-party services are a separate consented boundary)
- Future: the always-listed **geo-tier** + **on-device coarse names** optimizations; **derived** place from GPS
  clusters aggregated across chapters
