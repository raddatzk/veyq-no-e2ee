---
id: cr-0154
title: Web — fix the CLEAR violations from the ui-web-styleguide audit
status: done
satisfies: []
adrs: []
---

## Goal

A four-agent audit of `web/src` against `docs/crosscutting/ui-web-styleguide.md` (2026-07-13, follow-up to
cr-0152) surfaced real defects and clear styleguide violations. Fix the CLEAR findings now; the BORDERLINE /
normative ones are explicitly out of scope and feed the upcoming styleguide rewrite instead.

## Context & constraints

### Phase 1 — real defects (broken regardless of the guide)

- `.memory-card` is a white surface in dark mode: `memory.css` uses `--app-on-strong` (= `#fff` in both
  themes) as the card background and is the one such surface *not* re-backgrounded in `dark.css`, while
  `.memory-card-main` gets light text → light-on-white. Also its border is a raw slate
  (`rgb(226 232 240 / 0.78)`) with no dark value.
- Four tokens are referenced but defined nowhere (the declaration is invalid, so no shadow/color renders):
  `--app-shadow-lg` (layout.css, memory.css), `--app-shadow-soft` (memory.css), `--app-muted-text`
  (memory.css ×3), `--app-accent-strong` (settings.css). Map them to the existing tokens of the same role
  (`--app-shadow-md`, `--app-shadow-sm`, `--app-muted`, `--app-accent`) — reuse first, no new tokens.
- `AppSidebar.vue` compact groups-overflow button is icon-only with **no accessible name** — its full-size
  twin carries `aria-label` + `aria-haspopup`; the compact variant omits both.

### Phase 2 — raw colors in UI roles → tokens

- UI panel/card/dropdown shadows hardcoded as raw slate/black where `--app-shadow-sm/-md` exist (several
  are the *exact* token value): `.asset-bucket-*` rows/buttons, `.memory-scope-root-card`, `.memory-card`,
  `.suggest-panel`, `.uploads-panel`, `.auth-method-switch button.active`.
- Raw white panel backgrounds → express via `--app-surface` (`color-mix` keeps the alpha): `.login-card`,
  `.memory-create-card` (their dark.css overrides stay).
- `.support-notice` border = raw teal (light `--app-success` value, wrong in dark) → `color-mix` on
  `--app-success`.
- Favorite-heart formula `color-mix(in srgb, var(--app-accent) 45%, #e11d48)` duplicated in `MemoryCard.vue`
  + `MemoryDetail.vue` → one semantic token `--app-favorite` in `base.css` (both themes; the mix already
  tracks the theme-aware accent).
- Raw `#fff` as text/icon color on filled/accent surfaces → `var(--app-on-strong)`: `ShareView` (×2),
  `UploadStatusMenu` badge, `MemoryBuckets` danger dialog button, `MediaLightbox` cover-active.

### Phase 3 — dialog conformance

- Footers not using the shared row: `ShareCreateDialog` (`.share-create-actions` ×2) and
  `LocaleMarkdownEditor` (`.locale-md-dialog-actions`) → `.veyq-dialog-action-row` (cancel left, confirm
  right; source order = visual order).
- `TenantDetailPanel` action row ordering: primary submit sits mid-row with danger Revoke rightmost →
  `[Resend] … [.veyq-action-group: Revoke (danger outlined) | Reassign (primary)]`.
- In-progress protection (the styleguide's "disable confirm/cancel/close + inputs while the mutation runs"):
  the confirm is guarded everywhere, but cancel/close(X)/inputs stay live during the mutation in
  `DeleteNodeDialog`, `NodeMutationDialog`, `CreateGroupDialog`, `InviteMemberDialog`, `CreateTenantDialog`,
  `ChangedKeyReviewDialog`, `ShareCreateDialog`; the trash purge confirm (`MemoryDetail` + `useMediaTrash`)
  shows no in-progress state at all. Pattern to apply (the MemoryBuckets dialogs are the reference):
  `:closable="!busy"`, cancel/refuse `:disabled="busy"`, inputs `:disabled="busy"`; purge gets a real busy
  flag.

### Phase 4 — reuse + small a11y

- `ShareDialog` `.share-badge`/​`.share-badge.muted` duplicate `.veyq-badge`/​`.muted` verbatim → use the
  shared class.
- Phantom classes: `.veyq-form-error` (used in `MemoryBuckets`) and `.veyq-form-field` (used ×3 in
  `TenantJoinView`) are referenced but defined nowhere → define them in `forms.css` in-style (error =
  danger-colored form copy; field = labeled block), matching how sibling forms style the same roles.
- `ShareCreateDialog` readonly link input has no accessible name → `aria-label`.

### Out of scope (normative → styleguide rewrite)

`.veyq-loading` adoption vs. removal; icon-only PrimeVue buttons vs. `.veyq-icon-button` split;
`ChangedKeyReviewDialog` warn-colored Accept (arguably intentional caution); hand-rolled empties in
dropdown panels (`share-empty`, `uploads-empty`, `locale-md-empty`); glass insets on chrome
(dark-overridden, low); `GroupMembersPanel` inline-confirm ordering (not a dialog footer);
`memory-date-chip` ellipsis; map/Leaflet raw colors (rendered content, allowed).

### Verify

- `cd web && npm run check` green; `python3 scripts/validate_coverage.py` green.
- Live smoke on `veyq.localhost:8081`: browse grid in **light and dark** (memory-card readable in dark,
  shadows/borders intact), a guarded dialog (e.g. rename) shows disabled cancel/X/inputs while saving,
  purge confirm shows busy, share-create footer uses the shared row. Console clean.

## Phases

- [x] 1 web — real defects: memory-card dark surface/border, 4 undefined token references mapped to
  existing tokens, sidebar measure-clone made unfocusable (`tabindex="-1"` — the audit's "missing
  aria-label" was a false positive: the clone sits in an `aria-hidden` container; the real hazard was the
  off-screen tab stop).
- [x] 2 web — raw UI colors → tokens (shadows incl. a new `--app-shadow-card` for the repeated two-layer
  card shadow, white panels via `color-mix` on `--app-surface`, support-notice border, `--app-favorite`,
  `#fff` → `--app-on-strong`).
- [x] 3 web — dialog conformance: shared footer row ×2 (ShareCreateDialog also gained the missing Cancel),
  TenantDetailPanel ordering via `.veyq-action-group`, in-progress protection (`:closable`/
  `:close-on-escape`/cancel/inputs gated on busy) in 7 dialogs + purge busy state.
- [x] 4 web — reuse/a11y: share-badge → veyq-badge, phantom `.veyq-form-error`/`-field` defined in ui.css,
  link-input `aria-label` (reusing the existing `share.linkLabel` key).

## Definition of done

`npm run check` + coverage validator green; the CLEAR audit findings are fixed (dark-mode memory-card
readable, no undefined tokens, no raw UI-role colors at the listed sites, dialog footers/ordering/busy
protection conform, shared badge + defined form classes, no unlabeled icon-only control); live smoke
light+dark clean. BORDERLINE findings remain documented out-of-scope for the styleguide rewrite.

## Retrospective

Mechanically smooth; three findings refined during implementation. (1) The audit's sidebar a11y hit was a
false positive — the unlabeled button is an off-screen measure clone inside `aria-hidden`; the real defect
was that it stayed focusable (`tabindex="-1"` now). Verify agent findings against the DOM context before
fixing what they name. (2) The repeated two-layer card shadow earned a real token (`--app-shadow-card`)
instead of a lossy mapping onto `-sm`/`-md`; note the browse `.memory-card` is a PrimeVue Card whose own
shadow wins the cascade either way — the rule was dead before and after, so no visual change there.
(3) `share.linkLabel` already existed — adding "new" i18n keys without grepping first produced a TS1117
duplicate-key error; the check caught it. Live smoke (light + dark via the in-app System theme following
`prefers-color-scheme`): the dark browse card now renders `--app-surface` with readable text (the A1 bug),
the purge confirm and share-create footers sit on the shared row, console clean. The purge dialog now
holds open with a busy spinner instead of closing optimistically — slightly slower perceived, but the
irreversible action gets real feedback and a guarded close, per the guide.
