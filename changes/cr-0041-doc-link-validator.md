---
id: cr-0041
title: Docs link validator — every relative link under docs/ must resolve
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

The second cr-0038 retro follow-up, and the **reliable half of "are the ADRs still correct?"**: a link in
an ADR / use-case / enabler / crosscutting doc that points at a moved, renamed or deleted file is
unambiguously broken. `scripts/validate_doc_links.py` resolves every relative markdown link + image under
`docs/` and fails on any that doesn't exist.

## Context & constraints

- Chosen **instead** of a fuzzy "ADR reality drift" detector (floated in the cr-0038 discussion). Prose
  drift — an ADR describing a behaviour that has since changed — can't be judged mechanically without
  false-alarming on the rejected-alternative and hypothetical names ADRs deliberately contain. A **dead
  link** is the slice of "ADR correctness" that *is* machine-checkable with zero false positives, and it's
  the exact class behind cr-0038's ADR 0001 finding (a doc citing a path that no longer exists).
- External links (`http/https/mailto/tel`) and pure in-page anchors (`#section`) are out of scope; a
  `#fragment` and a link `"title"` are stripped before resolving. Targets resolve relative to the linking
  file and may point anywhere in the repo (e.g. `../../contracts/...`).

## What it surfaced

**5 dead links**, all the same off-by-one: docs at `docs/features/<area>/{use-cases,enablers}/` (four levels
below the repo root) linked to `../../../changes/cr-….md` — only three `../`, which resolves to the
non-existent `docs/changes/`. Fixed to `../../../../changes/…` in
`uc.account.biometric-unlock.md` and `en.spaces.personal-scope.md`.

## Definition of done

`python3 scripts/validate_doc_links.py` green (all relative links across 53 docs resolve); the 5 off-by-one
`changes/` links fixed.

## Retrospective

- **Process — these links were born broken and never noticed.** They aren't drift; they never worked. With
  no link check, a wrong `../` depth looks identical to a right one in source and is invisible until
  someone clicks it. Root cause: relative-depth links are easy to miscount and nothing verified them. Fix:
  landed here.
- **Assistant — none specific to this CR.** The tool was right on the first run; the only judgement call
  (skip externals + anchors, strip titles/fragments) held with zero false positives on 53 docs. Kept the
  scope deliberately narrow rather than over-reaching into prose-drift heuristics — the right call given the
  arc.
