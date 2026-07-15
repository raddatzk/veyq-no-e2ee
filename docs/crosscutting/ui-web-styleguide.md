# Web UI Styleguide

Visual + interaction rules for the **web** client, so extending the (reused) UI stays "in-style"
instead of by feel. veyq should feel like a curated private Memory archive — not a cloud drive, folder
gallery or social network.

## Source of truth

**`web/src/styles/`** — nine stylesheets (~175 KB: `base` tokens, `ui`, `layout`, `forms`, `memory`, `media`,
`settings`, **`dark`**), taken from `legacy/frontend-noe2ee/src/styles/` and then brought under the rules on
this page. `web/src/style.css` is the import barrel plus the few additions the pre-E2EE source never had (the
centered public shell, the Secret-Key unlock field).

**Not a verbatim copy — and deliberately not.** Only `media.css` is byte-identical; `base.css` and `ui.css` are
additive; the other five carry real edits, each commented with its reason. They are this page's own rules applied
to the inherited code, which is the useful thing to know about them:

- **raw colours became role tokens** — `rgba(15,23,42,.12)` → `var(--app-shadow-sm)`, i.e. *"repeated raw colors
  must become a role token before merge"* enforced backwards. Values shifted where the scale said so.
- **hardcoded light values became theme-aware** — `background: rgba(255,255,255,.92)` →
  `color-mix(in srgb, var(--app-surface) 92%, transparent)`. The original was white *by construction*; under
  `dark.css` it would have stayed white. This is the section's rule ("every semantic color defines both") fixing
  its own source.
- **E2EE-era components were appended** where the pre-E2EE product had no equivalent (`.veyq-form-field`,
  `.media-tile-check`, the changed-identity badge).

**There is nothing left to port.** The system arrived whole, not token by token — so the rule is not "port it
from legacy when you need it"; it is **use what is there, and if something is genuinely missing, that is a design
decision, not a copy job.** The source is `legacy/frontend-noe2ee/` (the pre-E2EE original — the clean product
UX; see its `_PROVENANCE.md`). `legacy/frontend/` is the **E2EE-but-wrong-concept** descendant: consult it only
for *how* an operator-blind server forces a flow client-side, **never** for its design. Canonical rule:
[`docs/ai/README.md`](../ai/README.md).

## Reuse first — this is the load-bearing rule

**The existing implementation is the styleguide.** `web/` is a whole product now, so the nearest built screen
answers almost every question this page could: use an existing `.veyq-*` shared class and `--app-*` token before
writing anything local. Add a local class only when no shared class can express the behaviour without leaking
styles elsewhere. Wrap a shared primitive in a feature component rather than copying markup.

**This gets stronger as web grows, and that is the design.** A rule that enumerates values goes stale the moment
the code moves; a rule that says *"match your neighbour"* cannot. It is also the one rule here that is
demonstrably obeyed — `.veyq-dialog-action-row`: 15 uses, zero per-component copies.

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

**Reuse the class; it already carries the size.** If a shared class fits, you never write `font-size` at all —
that is the whole answer, and §*Reuse first* is where it lives. When you genuinely must write CSS, **match the
neighbour**: the same row, the same panel, the same screen. Settings, management and navigation screens are
**dense and scannable** — reach for the smaller end, and do not leave a component library's default button text
or a one-off `1rem` label sitting in them.

> **There is no `--app-font-size-*` token family, and this section used to mandate one.** Zero existed; none ever
> did (`grep -r 'font-size: var(--app-' web/src` → 0). That was not an oversight to correct — **it was the rule
> that was wrong**, and this is worth stating so nobody "completes" the token system later:
>
> **Tokens exist here where theming forces them, not where tidiness suggests them.** Colour is 91% tokenised,
> background 75%, shadow 58% — those are exactly the axes `dark.css` cannot work without. The axes with no
> structural need were never tokenised and nobody missed them: **spacing is 578 declarations across 115 distinct
> raw values, 0% tokenised** — 2.4× worse than type, and no rule ever asked for `--app-space-*`. Type was the
> lone outlier demanding a token layer for a non-theming axis, and 89 CRs went by without anyone feeling its
> absence. That is the evidence, not an excuse.
>
> Imposing one now would mean choosing a scale the data does not contain (the most common value per role wins
> only 19–42% of its own uses), then editing **243 sites** and shifting the product's look — with no visual
> regression test anywhere, on a surface whose e2e tests no CI run executes. The cost is real and the defect is
> invisible. If a scale is ever genuinely wanted, it is an **ADR** with that migration priced in — not a line
> here, and writing the line did not make it exist.

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
