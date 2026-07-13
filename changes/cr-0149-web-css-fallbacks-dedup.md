---
id: cr-0149
title: Web CSS cleanup — strip dead --app-* var fallbacks + promote duplicated CSS to shared sheets (audit P2a rest)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

The deferred remainder of audit finding #64 (**P2a**), split off from cr-0129 (which fixed the two *real*
dark-mode bugs — the phantom `--app-warn`/`--app-border` tokens). Two cosmetic-but-worth-it cleanups:

1. **Strip the dead `var(--app-*, <fallback>)` fallbacks** — 41 sites where the token **does** exist in
   `styles/base.css`, so the literal/var fallback is dead code that only misleads (it reads like the color might
   be `#71717a` when it's really the theme token). Stripping to bare `var(--app-*)` is a **runtime no-op** (the
   fallback never fires) that removes the misleading duplication.
2. **Promote duplicated CSS blocks to shared sheets** — `.settings-muted` (6× byte-identical) and
   `.invite-link-row`/`.invite-link-input` (4× byte-identical) collapse to one shared definition each; the
   ShareView `.gallery-*` header trio de-duplicates against MemoryDetail.

## Context & constraints

### Phase 1 — strip 41 dead `var(--app-*, …)` fallbacks (mechanical, zero visual change)

All 41 use a token declared in `styles/base.css` (light `:root` + dark block), so `var(--app-x, y)` ≡
`var(--app-x)` at runtime — stripping the fallback changes nothing rendered, only removes dead/misleading code.
By token (13 files):
- `--app-muted` ×10 — `UploadStatusMenu.vue` (197/202/209/219/254/269/276), `SettingsHubView.vue`
  (265/287/296, these fall back to `var(--p-text-muted-color)` not a hex — same dead-fallback pattern).
- `--app-danger` ×8 — `MemoryBuckets.vue` (543/567), `OptimisticTile.vue` (121), `ShareCreateDialog.vue` (321),
  `ShareDialog.vue` (260), `ShareView.vue` (594), `UploadStatusMenu.vue` (257/264).
- `--app-accent` ×8 — `MemoryLocationEditor.vue` (238/260), `MemoryMap.vue` (132), `SpaceMap.vue` (339/421),
  `UploadStatusMenu.vue` (163/231/260).
- `--app-surface` ×6 — `KitScanner.vue` (92), `TenantTermsPanel.vue` (177), `MemoryBuckets.vue` (565),
  `SpaceMap.vue` (371/389), `UploadStatusMenu.vue` (179).
- `--app-surface-muted` ×3 — `ShareCreateDialog.vue` (271), `ShareDialog.vue` (315), `ShareView.vue` (660).
- `--app-radius` ×2 — `SpaceMap.vue` (311/322).
- Singletons ×4 — `EmergencyKitSave.vue` (61, `--app-success`), `TenantTermsPanel.vue` (175 `--app-line` / 176
  `--app-radius-sm`), `SpaceMap.vue` (420 `--app-accent-soft`).

Strip the fallback only — **do not** touch the token name (those were already fixed in cr-0129; the phantom
`--app-warn`/`--app-border` are confirmed fully absent, nothing to do there).

### Phase 2 — promote the flat duplicated blocks to `styles/settings.css`

- **`.settings-muted`** — byte-identical `{ margin: 0; color: var(--app-muted); }` in 6 files
  (`GroupMembersPanel.vue`, `TenantMembersPanel.vue`, `TenantTermsPanel.vue`, `TenantDetailPanel.vue`,
  `ProfilePanel.vue`, `GroupInvitationsView.vue`). Move one copy to `styles/settings.css`; delete the 6 local
  `<style>` copies.
- **`.invite-link-row` / `.invite-link-input`** — byte-identical 2-rule block in 4 files
  (`TenantMembersPanel.vue`, `InviteMemberDialog.vue`, `CreateTenantDialog.vue`, `TenantDetailPanel.vue`). Move
  one copy to `styles/settings.css`; delete the 4 local copies.
- Confirm `styles/settings.css` is imported globally via `styles/*`/`style.css` before removing the local copies
  (the classes are consumed from component templates, so a single global definition suffices).

### Phase 3 — de-duplicate the ShareView `.gallery-*` header trio

`ShareView.vue` (597/602/608) redefines `.gallery-header-copy` / `.gallery-title` / `.gallery-count` with the
**same declarations** as `MemoryDetail.vue` (976/1033/1039) — a scoped copy, because MemoryDetail's are
component-scoped and invisible to ShareView. Promote the three shared rules to `styles/memory.css` (already the
home of the shared media-grid system) and remove both the MemoryDetail and ShareView scoped copies. The rest of
`.gallery-*` is already single-source (grid/tile long since promoted to `.media-grid-*`), so this closes the
`.gallery-*` dedup. **This is the one part with visual-regression surface** (scoped → global) — covered by the
live smoke below. If MemoryDetail's copies turn out entangled with adjacent scoped rules, descope Phase 3 to
just dropping ShareView's redundant override and fold the promotion into #77 (which owns MemoryDetail).

### Verify

- `cd web && npm run check` (eslint + `vue-tsc` + test + build).
- **Live light/dark visual smoke** (the check cr-0129 deferred, now doable — a session is up on
  `veyq.localhost:8081`): the affected surfaces render identically before/after in **both themes** — settings hub
  (muted text), invite dialogs + member panels (invite-link rows + muted captions), the share view + gallery
  header, and a spot-check of the upload-status menu / maps (the bulk of the stripped fallbacks). No color/spacing
  regression, clean console.

## Phases

- [x] 1 web — stripped the 41 dead `var(--app-*, …)` fallbacks to bare `var(--app-*)` across the 13 files (via a
  balanced-paren transform handling the 3 nested-`var()` fallbacks). Verified: exactly 41 replacements, **0
  residual** `var(--app-*,`; the `var(--p-*, var(--app-*))` PrimeVue-token cases correctly untouched.
- [x] 2 web — promoted `.settings-muted` (6×) + `.invite-link-row`/`.invite-link-input` (4×) to
  `styles/settings.css`; deleted the 10 local scoped copies. Class-usage parity verified 1:1 (no element newly
  styled). `npm run check` green.
- [x] 3 web — promoted the `.gallery-header-copy`/`.gallery-title`/`.gallery-count` trio to `styles/memory.css`;
  dropped MemoryDetail's base copies (its `@media` `.gallery-title` responsive override kept) + ShareView's
  scoped copies. The 3 classes are used only in those two components; `memory.css` is globally imported. `npm run
  check` green (595 tests, build OK).

## Definition of done

`npm run check` green; no `var(--app-*, …)` fallback remains in `web/src`; `.settings-muted` +
`.invite-link-*` live in one shared sheet with the local copies gone; the `.gallery-*` header trio is
single-source. Live light/dark smoke shows no visual regression on the touched surfaces. No token renames (that
was cr-0129); purely dead-code + duplication removal.

## Retrospective

- **Mechanical done mechanically.** The 41 fallback strips + 14 block removals were driven by small Python
  transforms with exact-string / balanced-paren matching and post-run residual assertions, not 55 hand-edits —
  faster and self-verifying (every removal asserted the block was present, every run counted residuals to 0).
- **Parity check before promotion.** Before moving any scoped rule global, confirmed the class is used *only* in
  the files that defined it (settings-muted 6/6, invite-link 4/4, gallery trio 2/2) — so global promotion is a
  provable 1:1 dedup, not a behaviour change that newly styles other elements.
- **Live light/dark smoke completed (the check cr-0129 had to defer).** On the running session: the promoted
  gallery header renders exact computed values — `.gallery-title` 32px/margin 0/lh 35.2px, `.gallery-count`
  `--app-muted` (light `rgb(102,112,133)` → **dark** `rgb(154,167,181)`), `.gallery-header-copy` grid/gap 4.8px —
  plus a clean dark-mode screenshot of the detail. The promoted `styles/settings.css` rules were verified global
  by probing injected elements: `.settings-muted` themes light→dark on the same `--app-muted` token,
  `.invite-link-row` = flex/gap 8px/center, `.invite-link-input` = flex 1/mono/13.6px — matching the promoted
  declarations. No regression in either theme; Phase 1 remains a provable runtime no-op.
