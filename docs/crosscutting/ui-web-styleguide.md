# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

- **Canonical, current:** `web/src/style.css` — the live `--app-*` tokens and `.veyq-*` classes. It is
  deliberately a **minimal subset** today (setup slice; light-mode only) and **grows** as UI lands.
- **The full system to port from:** `legacy/frontend` styles + components (read-only reference). When a
  token/class you need isn't in `web/src/style.css` yet, **port it from legacy in-style** — same names,
  same roles — rather than invent a new one. (See the frontend-reuse rule in `docs/ai/README.md`.)

## Reuse first

Before adding any local style: use an existing `.veyq-*` shared class and `--app-*` token. Add a local
class only when no shared class can express the behaviour without leaking styles elsewhere. Wrap a shared
primitive in a feature component rather than copying markup.

## Color — semantic role tokens

Use `--app-*` role tokens before raw colors. **Every semantic color must define both light and dark**
(today's `style.css` is a light-only subset — add the dark value when you grow it):

- **accent / brand** — brand marks, selected states, focus accents, navigation highlights.
- **success / positive** — submit, save, confirm, upload, consent, progress, maps, tracks, journal links.
- **warning** — group/shared/context badges, warm status; never for submit.
- **danger** — destructive actions, removal, deletion, errors only.
- **info** — informational/helper cues, non-critical metadata.
- **control / surface / text / line** — neutral controls, panels, foreground, borders.

Raw colors are allowed only for *rendered content* (lightbox overlays, media scrims, map/SVG/canvas
values), never for application UI roles. Repeated raw colors must become a role token before merge.

## Typography

Use the `--app-font-size-*` role tokens (page-title, section-title, row-title, body, subtitle, meta,
label, control). Settings/management/navigation screens should feel **dense and scannable** — avoid
one-off `1rem` labels or default component-library button text there.

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
- Dialog footers (`.veyq-dialog-action-row`): **cancel/secondary left, submit/confirm/destructive
  right**; source order matches visual order. Confirm/destructive actions show an in-progress state and
  **disable confirm/cancel/close + inputs while the mutation runs** (no double-submit).

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
- Defined light **and** dark for any new semantic color.
- Memory/Chapter language; folders secondary; no Album/Explorer.
- Rows for management data, cards for content; no nested cards.
- Dialog actions ordered + in-progress-safe; icon-only actions labelled.
- Ported missing tokens/classes from legacy **in-style** rather than inventing.
