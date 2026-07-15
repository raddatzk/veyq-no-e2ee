# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

**`web/src/styles/`** — nine stylesheets (~175 KB: `base` tokens, `ui`, `layout`, `forms`, `memory`, `media`,
`settings`, **`dark`**), **vendored verbatim** from `legacy/frontend-noe2ee/src/styles/`. Components reuse the
original class names and markup so they render byte-identically; only the data layer underneath differs.
`web/src/style.css` is the import barrel plus the handful of additions the pre-E2EE source never had (the
centered public shell, the Secret-Key unlock field) — read its header comment, it says this too.

**There is nothing left to port.** The design system arrived whole, not token by token. So the rule is not
"port it from legacy when you need it" — it is **use what is there, and if it is genuinely missing, that is a
design decision, not a copy job.** The source is `legacy/frontend-noe2ee/` (the pre-E2EE original — the clean
product UX). `legacy/frontend/` is the **E2EE-but-wrong-concept** descendant: consult it only for *how* an
operator-blind server forces a flow client-side, **never** for its design. Canonical rule:
[`docs/ai/README.md`](../ai/README.md).

## Reuse first

Before adding any local style: use an existing `.veyq-*` shared class and `--app-*` token. Add a local
class only when no shared class can express the behaviour without leaking styles elsewhere. Wrap a shared
primitive in a feature component rather than copying markup.

## Color — semantic role tokens

Use `--app-*` role tokens before raw colors — they are defined in `styles/base.css`, and **every semantic color
carries a dark value in `styles/dark.css`**. A new one must define both:

- **accent / brand** — brand marks, selected states, focus accents, navigation highlights.
- **success / positive** — submit, save, confirm, upload, consent, progress, maps, tracks, journal links.
- **warning** — group/shared/context badges, warm status; never for submit.
- **danger** — destructive actions, removal, deletion, errors only.
- **info** — informational/helper cues, non-critical metadata.
- **control / surface / text / line** — neutral controls, panels, foreground, borders.

Raw colors are allowed only for *rendered content* (lightbox overlays, media scrims, map/SVG/canvas
values), never for application UI roles. Repeated raw colors must become a role token before merge.

## Typography

**There is no type scale, and this section used to pretend there was.** It mandated `--app-font-size-*` role
tokens (page-title, section-title, row-title, body, subtitle, meta, label, control) — **zero of which exist**,
and none ever did: `grep -r 'font-size: var(--app-' web/src` → 0. The vendored system sets sizes as plain `rem`,
and the values in use cluster around `0.78 / 0.8 / 0.82 / 0.85 / 0.9 / 0.92 / 0.95 / 1rem`. An unfollowable rule
is worse than no rule: the reader greps, finds nothing, and either invents a token family or quietly ignores the
section — and both happened.

The followable rule, which is the one that carried the intent anyway: **match the density of what surrounds
you.** Settings, management and navigation screens are **dense and scannable** — reach for the smaller end, and
do not leave a component library's default button text or a one-off `1rem` label sitting in them.

Naming a real scale is a **design decision** — an ADR, and a migration of the ~100 raw values. It is not a
styleguide line, and writing one did not make it exist.

## Components

- **Surfaces & cards:** `.veyq-surface` for framed panels; `.veyq-card` for repeated content objects;
  `.veyq-card-interactive` only when the card is clickable. **No nested cards.** Page sections are
  normally unframed.
- **Rows vs cards:** rows (`.veyq-list`, `.veyq-list-row`, `.veyq-row-main/-title/-subtitle/-meta`) for
  management/dense data; cards for repeated content objects (Memories, media, tracks). Rows collapse to
  one column on mobile — don't hide essential content in trailing action columns.
- **Badges:** `.veyq-badge.success|.warning|.muted` for status; no one-off pills.
- **Forms:** `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`; full-width inputs; concise labels;
  muted helper text that explains *consequences*, not the label.
- **Empty/loading:** `.veyq-empty`, `.veyq-loading` — concise and action-oriented, not marketing copy.

## Buttons & dialog actions

- Submit/save/confirm = positive (green); cancel/neutral = secondary; destructive = danger with an
  explicit label. Icon-only actions use `.veyq-icon-button` + an `aria-label`/tooltip.
- **Dialog footers use the one shared `.veyq-dialog-action-row`** (a `space-between` row; never a
  per-component `.dialog-actions` copy): **cancel/secondary anchored left, submit/confirm anchored right**;
  source order matches visual order. A **destructive tertiary** action that coexists with the primary confirm
  (e.g. *Cover entfernen* / *Ort entfernen*) sits in a trailing `.veyq-action-group` **immediately left of the
  confirm**, styled **`severity="danger" outlined`** (danger colour, but lighter than the filled primary so it
  doesn't compete). When the destructive action **is** the dialog's primary confirm (a dedicated delete/confirm
  dialog), it is a **filled `severity="danger"`** button on the right — the dialog's one primary. Confirm/
  destructive actions show an in-progress state and **disable confirm/cancel/close + inputs while the mutation
  runs** (no double-submit).

## Product language

**Memory / Chapter** (DE: *Kapitel*) for content; folders are organizational and visually **secondary**
to Memories. Never reintroduce *Album*/*Explorer*. Public/sharing UI must not imply public profiles,
cross-Memory person discovery, feeds, reactions or a social graph.

## Accessibility & responsiveness

Visible focus states, accessible names, predictable keyboard behaviour. No hover-only affordances for
essential actions. Text must not overflow on mobile — prefer existing responsive `.veyq-*` behaviour
before component-specific media queries.

## Checklist before finishing web UI work

- Reused a `.veyq-*` class and `--app-*` token before adding local styles / raw colors.
- Defined light **and** dark (`styles/dark.css`) for any new semantic color.
- Memory/Chapter language; folders secondary; no Album/Explorer.
- Rows for management data, cards for content; no nested cards.
- Dialog actions ordered + in-progress-safe; icon-only actions labelled.
- Matched the surrounding density rather than introducing a size of your own.
