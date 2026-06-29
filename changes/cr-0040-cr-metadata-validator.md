---
id: cr-0040
title: CR-metadata validator — number/id uniqueness + satisfies/adrs resolve (+ quote the mangled adrs)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Tooling follow-up from cr-0038's retrospective: make the *cheap, deterministic* drift classes fail fast
instead of being caught by a human audit. `scripts/validate_changes.py` validates every
`changes/cr-NNNN-slug.md`'s frontmatter and its links into the docs tree.

## Context & constraints

- Born from the cr-0038 retro ("a duplicate CR number / stale links accumulated because nothing checks
  them"). Deliberately **not** the fuzzy "ADR-drift detector" that was floated — that would false-alarm on
  ADRs citing *rejected* alternatives, re-creating the false-green this whole arc removed. This guard only
  asserts things that are unambiguously right-or-wrong.
- Python per the scripts rule; reuses PyYAML like `validate_coverage.py`.

## Checks

Per `cr-NNNN-slug.md`:
- the NNNN in the filename is **unique** across all CRs (the cr-0036 collision);
- frontmatter `id` equals `cr-NNNN` (filename ↔ id agree);
- `status` ∈ {planned, in-progress, done};
- every `satisfies:` id resolves to a **use-case** (`uc.*`) **or enabler** (`en.*`) doc;
- every `adrs:` id is a **quoted 4-digit string** and resolves to `docs/decisions/NNNN-*.md`.

## The latent bug it surfaced

`adrs: [0001, 0004, 0009, 0013]` is **not** what it looks like. Unquoted in YAML, `0013` parses as octal
**11**, `0009` (invalid octal) as the string `"0009"`, `0001`→`1` — so ADR references in CR frontmatter were
silently the *wrong* number or a different type across **35 CRs**. Fixed by quoting every id
(`adrs: ["0013"]`); the validator now rejects any bare (int) adr id with that explanation, so it can't
regress.

The `satisfies:` check also corrected my first assumption that only use-cases are satisfiable — 6 CRs
legitimately satisfy an **enabler** (e.g. `en.memory.tree`, `en.spaces.group-scope`), so both are accepted.

## Definition of done

`python3 scripts/validate_changes.py` green (39 CRs, all numbers unique, ids/satisfies/adrs resolve); all
adrs ids quoted; the duplicate-number, bad-id, dead-satisfies and bare-adr cases each fail with a clear
message.

## Retrospective

- **Assistant — my first quote-fixer ate the newline.** A `re.match` whose tail group stopped at `$`
  (before `\n`) and a rebuilt line without the newline re-appended merged every `adrs:` line into the
  following `invariants:` line across 35 files. Caught immediately because the next thing I ran (the
  validator) failed to parse the YAML — but I'd have caught it *before* running by previewing one file's
  diff. Root cause: bulk-rewriting 35 files in one shot with no single-file dry-run. Fix: on any multi-file
  text rewrite, diff one file first; `git checkout --` made the recovery trivial precisely because cr-0038
  was already committed.
- **Assistant — I assumed `satisfies:` meant use-cases only.** The validator then "failed" on 6 CRs that
  correctly satisfy enablers. The tool was wrong, not the data. Root cause: I encoded a rule from memory
  instead of checking the existing CRs first. Cheap fix would've been to grep `satisfies:` across CRs before
  writing the check.
- **Process (good) — the guard paid for itself on first run** by finding the octal-mangled adrs, a real
  silent defect no human had noticed across 35 CRs. That's the argument for cheap deterministic guards over
  audits.
