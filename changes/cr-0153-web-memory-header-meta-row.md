---
id: cr-0153
title: Web Memory header — date + location chips side by side, no mid-phrase wrap
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Reported UI bug on the Memory detail header: the location chip ("Kein Ort erkannt") wrapped mid-phrase to two
lines and stacked **below** the date instead of sitting **beside** it, even at wide widths where there is room.

## Context & constraints

**Root cause was the HTML structure, not the CSS** (the reporter spotted it): the header put the cover **and all
three info lines** (title, count, meta) in one box (`.gallery-header-lead > .gallery-header-copy`) with the action
bar as a **sibling beside it**. So the info column and the actions competed for the same horizontal space — the
date/location row (3rd line) was squeezed by the actions even though it should never have to share a row with
them. No amount of growing/wrapping/breakpointing fixes that; the boxes are wrong.

**Fix — restructure the header** so the actions live only in row 1:

```
.gallery-header (row)
  .memory-cover-preview                 ← cover, left
  .gallery-header-body (column)         ← grows to fill
    .gallery-header-titlerow (row, space-between)   ← row 1: title | actions
    .gallery-count                      ← row 2
    .memory-meta-row                    ← row 3: date · location
```

Now rows 2 and 3 span the **full body width** (header − cover) regardless of the action bar, so the date +
location chips sit side by side at any width. `.memory-date-chip { white-space: nowrap }` keeps each label on one
line. The action bar is `.gallery-header-actions` moved inside `.gallery-header-titlerow`; on a genuinely narrow
header (`@container memory-detail-surface (max-width: 540px)`, on the `.memory-detail` query container so it keys
off the real header width, not the sidebar-inflated viewport) the cover stacks above the body. MemoryDetail's own
header markup only — ShareView's shared `.gallery-header-copy` (cr-0149) is untouched.

Verified live on `veyq.localhost:8081` at header ~510px content (viewport 655) and ~700px (viewport 1050): row 1
title + actions, row 2 count, row 3 date + location **side by side** (meta row 510px, no squeeze); cover left;
gallery grid unaffected. `npm run check` green (608 tests, build OK); no behaviour change (same buttons + click
targets).

## Phases

- [x] 1 web — (superseded) grow the title column + `nowrap` — helped only above a viewport threshold.
- [x] 2 web — (superseded) full-width meta row below the whole header — robust but moved the chips below the cover.
- [x] 3 web — (superseded) container-query the actions to their own row — kept the wrong box structure.
- [x] 4 web — **restructure the header**: cover + a `.gallery-header-body` column whose row 1 is
  `.gallery-header-titlerow` (title + actions), row 2 the count, row 3 the meta; so the count + meta rows span the
  full body width and the date/location chips are always side by side. Mobile cover-stack via `@container`. `npm
  run check` green; live-verified narrow + wide.

## Definition of done

`npm run check` green; the header reads **row 1 = title + actions, row 2 = count, row 3 = date · location side by
side** at any width, because the info rows no longer share horizontal space with the action bar. Labels never
break mid-phrase. Purely a header layout restructure; no data/behaviour change.

## Retrospective

- **The reporter diagnosed it: fix the boxes, not the CSS.** Three CSS-only attempts (grow, full-width-below,
  container-stack-actions) all fought a structure that put the info column and the actions in competing sibling
  boxes. Putting the actions inside row 1 of a body column — so rows 2/3 own their full width — made the layout
  trivially correct at every width. Restructure beat tuning.
- **Trust the report over the measurement.** Each "still broken" was real; the durable fix came only after
  stepping back to the structure instead of defending the latest CSS rule.
- **Kept the shared class shared.** The restructure lives in MemoryDetail's own header markup + scoped CSS;
  ShareView's `.gallery-header-copy` (cr-0149) is unaffected.
