---
id: cr-0129
title: Fix web dark-mode token bugs — --app-warn→--app-warning, --app-border→--app-line
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Two real dark-mode bugs from the code-quality audit: `--app-warn` and `--app-border` are **not defined
tokens**. The design system defines `--app-warning` / `-text` / `-soft` / `-line` and `--app-line` (verified in
`web/src/styles/base.css`, with distinct light + dark values). So every `var(--app-warn, #hex)` /
`var(--app-border, #hex)` rendered its **hardcoded fallback in both light and dark** (no theming), and the two
`memory.css` usages had **no fallback at all** — an undefined token → an invisible border. Renamed the 11 sites
to the real, dark-mode-aware tokens and dropped the now-dead fallbacks.

## Context & constraints

- **Verified against source:** `base.css` defines `--app-warning` (`:15` light / `:71` dark) + `-soft`/`-line`/
  `-text`, and `--app-line` (`:7` / `:63`); `--app-warn` and `--app-border` appear in **no** definition. The 11
  broken sites (from grep): `ShareView.vue` ×4, `UploadStatusMenu.vue` ×3, `GroupMembersPanel.vue` ×1,
  `TermsAcceptView.vue` ×1, `memory.css` ×2.
- **Token role respected:** `ShareView`'s uploader-badge/note `color:` → `--app-warning-text` (the readable
  on-warning text tone, dark-aware); its two `color-mix()` border/background bases → `--app-warning`. Every
  `--app-border` → `--app-line`.
- **Deliberate visual consequence:** the uploader warning now follows the `--app-warning` palette (a
  theme-aware yellow-based tone) instead of the prior hardcoded orange (`#b45309`). Adopting the system is the
  point; fine-tuning the exact hue is a separate design tweak, not a bug fix.
- **Deferred (separate cleanup, not a bug):** the 36 cosmetic `var(--app-*, #hex)` fallbacks where the token
  **does** exist (dead-but-harmless), and the duplicated CSS blocks (`.gallery-*`, `.invite-link-*`,
  `.settings-muted`) the audit flagged for promotion to shared sheets.
- **Verify:** `cd web && npm run check` (typecheck + test + build). A visual light/dark smoke needs a running
  backend + login, so it stays a manual step (deferred to the user), consistent with the other web CRs.

## Phases

- [x] 1 web — renamed the 11 sites in `ShareView.vue`, `UploadStatusMenu.vue`, `GroupMembersPanel.vue`,
  `TermsAcceptView.vue`, `styles/memory.css`; dropped the dead hex/rgba fallbacks. · **Done:** `npm run check`
  **green** (vue-tsc + vitest + vite build, built in ~0.7s); trust-chain guards unaffected.

## Definition of done

`npm run check` green; no `var(--app-warn…)` or `var(--app-border…)` remains; the converted sites use the real
`--app-warning*` / `--app-line` tokens.

## Retrospective

- **Scope discipline:** shipped only the two *real bugs* (undefined tokens → no theming / invisible border) and
  deferred the 36 dead-but-harmless hex fallbacks + the CSS-dedup, rather than churn ~15 files in one pass.
- **Honest limit:** verified statically (token exists in `base.css`, `npm run check` green) but did **not** do a
  visual light/dark smoke — it needs a seeded backend + login, disproportionate for a token rename. Flagged as a
  manual follow-up rather than claimed.
- **Next time:** nothing notable; a clean mechanical fix.
