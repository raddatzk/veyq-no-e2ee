---
id: cr-0121
title: Memory header — date + location as one metadata row, "no location detected" label
status: done
satisfies: [uc.memory.place]
adrs: ["0016"]
invariants: [operator-blind-e2ee]
---

## Goal

Two web-only polish items on the Memory header (`MemoryDetail.vue`), from the user reviewing the "test" Memory:

1. **Date + location side by side, lighter.** They were two stacked bordered pills. Now they sit **on one metadata
   row** under the title as **borderless** buttons (accent icon + label, subtle hover) — still each opens its editor
   (date / location). The "set / pending / no-location" placeholder states render **muted** (`is-empty`); a real,
   named place renders in full text.
2. **"Kein Ort erkannt" instead of "Ort wird ermittelt …" when there's nothing to resolve.** The pending label
   (armed `auto` place, no name yet) implied an in-progress lookup even when the Memory has **no geotagged media** —
   so it would never resolve. Now it says **"Ort wird ermittelt …" only when a GPS position exists** (a name is
   genuinely still being derived) and **"Kein Ort erkannt"** when there are no GPS points. New i18n key
   `memory.place.none`.

## Context & constraints

- **Web-only, one component + a label.** `MemoryDetail.vue` (the header template + scoped CSS + a `locationResolved`
  computed and the `locationLabel` branch) and `web/src/i18n/index.ts` (`memory.place.none`, en + de). No
  contract/backend/crypto change; operator-blind untouched (the location is still client-decrypted from the sealed
  node meta; the "no location" decision reads only local GPS-point presence).
- **Verify:** `cd web && npm run check` (the two `MemoryDetail` location tests split into: no geotagged media → "no
  location detected"; a geotagged medium present but unconsented geocoding → still "pending"); a live smoke deferred
  to the user (preview logged out) — the date + location render on one lighter row, and an armed auto place with no
  GPS media reads "Kein Ort erkannt".

## Phases

- [x] 1 **web** — `MemoryDetail.vue`: wrap the date + location chips in a `.memory-meta-row`; restyle the chips
  borderless (accent icon, hover, muted placeholder); `locationLabel` returns `place.none` when pending with no GPS
  points, `place.pending` when a position exists; `locationResolved` drives the location chip's muted state. i18n
  `memory.place.none`. · **Done:** `npm run check` green, guards green.
- [x] 2 **apple / android** — **n/a** (web-only header polish).
