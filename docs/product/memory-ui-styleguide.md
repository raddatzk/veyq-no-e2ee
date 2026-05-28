# veyq Memory UI Styleguide

This guide defines the default visual and interaction rules for veyq product UI. It is intended for Codex, agents and contributors making frontend or product-facing changes.

veyq should feel like a curated private Memory archive. It should not look or behave like a generic cloud drive, folder gallery or social network.

## Foundations

Use the shared UI layer in [`frontend/src/styles/ui.css`](../../frontend/src/styles/ui.css) before adding feature-specific styles. New components should start with the generic `.veyq-*` classes and add local classes only for domain-specific layout, media handling or exceptional states.

The shared layer provides:

- Page and section layout: `.veyq-page`, `.veyq-page-narrow`, `.veyq-section`, `.veyq-section-header`, `.veyq-section-count`.
- Surfaces and cards: `.veyq-surface`, `.veyq-card`, `.veyq-card-interactive`.
- Empty and loading states: `.veyq-empty`, `.veyq-loading`, `.veyq-empty-inline`, `.veyq-empty-action`.
- Actions and buttons: `.veyq-action-row`, `.veyq-action-group`, `.veyq-button-row`, `.veyq-dialog-action-row`, `.veyq-icon-button`, `.veyq-text-button`.
- Forms: `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`.
- Lists and rows: `.veyq-list`, `.veyq-list-row`, `.veyq-row-main`, `.veyq-row-title`, `.veyq-row-subtitle`, `.veyq-row-meta`.
- Badges and dialog buttons: `.veyq-badge`, `.veyq-dialog-button`, `.veyq-dialog-button-primary`, `.veyq-dialog-button-secondary`, `.veyq-dialog-button-danger`.

Memory-specific classes such as `.memory-card`, `.memory-detail-*`, `.memory-upload-action`, `.memory-icon-action` and `.memory-tabs` are the Memory feature layer. Do not use them as generic app primitives outside Memory-oriented surfaces.

## Color System

The baseline tokens live in [`frontend/src/styles/base.css`](../../frontend/src/styles/base.css). Use tokens first. Add new raw colors only when the value is local to a concrete visual object and does not represent a reusable semantic role.

Primary / brand:

- Light: `--app-accent: #5b4df5`, `--app-accent-soft: #f0eeff`.
- Dark: `--app-accent: #9b8cff`, `--app-accent-soft: #2a2541`.
- Use for brand marks, selected states, Memory creation cues, focus accents and navigation highlights.

Submit / positive action:

- Light: `--app-success: #0f766e`, `--app-success-strong: #115e59`, `--app-success-soft: #ecfdf5`, `--app-success-line: #bbf7d0`, `--app-success-text: #166534`.
- Dark: `--app-success: #2dd4bf`, `--app-success-strong: #5eead4`, `--app-success-soft: #132b21`, `--app-success-line: #14532d`, `--app-success-text: #86efac`.
- Use for submit, save, confirm, upload, consent, progress, journal links, maps and tracks.

Warm / contextual warning:

- Light: `--app-warning: #facc15`, `--app-warning-soft: #fffbeb`, `--app-warning-line: #fde68a`, `--app-warning-text: #92400e`.
- Dark: `--app-warning: #facc15`, `--app-warning-soft: #2f2413`, `--app-warning-line: #713f12`, `--app-warning-text: #fde68a`.
- Use for group/shared/context badges, warning metadata and warm status cues. Do not use this color for submit actions.

Danger:

- Light: `--app-danger: #b42318`, `--app-danger-strong: #b91c1c`, `--app-danger-soft: #fef2f2`, `--app-danger-line: #fecaca`.
- Dark: `--app-danger: #f87171`, `--app-danger-strong: #fca5a5`, `--app-danger-soft: #3b1d24`, `--app-danger-line: #7f1d1d`.
- Use only for destructive actions, removal, deletion and errors.

Info / helper:

- Light: `--app-info: #2563eb`, `--app-info-soft: #eef4ff`, `--app-info-line: #9db7f8`, `--app-info-text: #1e3a8a`.
- Dark: `--app-info: #67e8f9`, `--app-info-soft: #1d3340`, `--app-info-line: #155e75`, `--app-info-text: #67e8f9`.
- Use for informational helper states, search/folder helper cues and non-critical blue/cyan metadata.

Control and strong contrast:

- `--app-on-strong` is the readable foreground on strong accent, success, danger or media-backed surfaces.
- `--app-control-bg` and `--app-control-text` are for neutral controls, inputs and control-like rows.
- `--app-backdrop-strong` is for reusable dark backdrops and strong media placeholders, not for general page backgrounds.

Surfaces, text, lines, hover, focus, radius and shadows should come from the existing `--app-*` tokens. Every new semantic color must define both light and dark mode behavior. New frontend work should complete a token pass before merging: repeated raw colors must be converted to role tokens instead of copied into component CSS.

Raw colors are allowed only for concrete visual content, for example Lightbox black overlays, transparent media scrims, map/SVG/canvas drawing values and object-specific gradients where the color is part of the rendered asset rather than an application UI role.

## Buttons

Prefer PrimeVue buttons for standard application actions, especially where the surrounding UI already uses PrimeVue. Use PrimeIcons for icon actions when a clear icon exists.

Button roles:

- Submit, save and confirm actions use the green positive action color. For native buttons in custom forms or dialogs, use `.veyq-dialog-button.veyq-dialog-button-primary`.
- Cancel and neutral actions use `.veyq-dialog-button-secondary`, `severity="secondary"` or a neutral surface treatment.
- Destructive confirmations use `.veyq-dialog-button-danger` or PrimeVue danger styling. Labels must be explicit about the destructive action.
- Compact toolbars use `.veyq-icon-button` with an accessible `aria-label` or tooltip.
- Text-like utility actions use `.veyq-text-button`.
- Dialog action buttons use icons consistently: cancel uses `pi pi-times`, generic confirm uses `pi pi-check`, explicit save uses `pi pi-save`, delete uses `pi pi-trash`, and move uses the closest PrimeIcon for that action.

Action layout:

- Use `.veyq-button-row` for simple inline groups of buttons, such as settings-section utilities or one-off inline actions without a cancel/submit pair.
- Use `.veyq-dialog-action-row` as the standard for dialog footers and form-ending dialog actions. Cancel or secondary actions go on the left; submit, save, confirm, move or destructive actions go on the right. It wraps on narrow screens and gives buttons full width on very small viewports.
- Use `.veyq-action-row` with `.veyq-action-group` when content and actions need to sit on opposite sides.
- Keep dialog action source order aligned with the visual order: cancel first, primary or destructive action second. This keeps keyboard and mobile behavior predictable.

## Lists, Rows and Badges

Use `.veyq-list` and `.veyq-list-row` for management rows such as settings, shares, users and groups.

Rows should use:

- `.veyq-row-main` for the text block.
- `.veyq-row-title` for the primary label.
- `.veyq-row-subtitle` for secondary information such as email, path, URL or description.
- `.veyq-row-meta` for badges and compact metadata.

Use `.veyq-badge.success`, `.veyq-badge.warning` and `.veyq-badge.muted` for status metadata. Do not create one-off pill styles unless a new repeated semantic status is needed.

Each row should remain readable at mobile widths. Avoid placing essential content only in the trailing action columns because `.veyq-list-row` collapses to one column on narrow screens.

## Cards, Surfaces and Empty States

Use `.veyq-surface` for framed panels and settings-like areas. Use `.veyq-card` for repeated content objects. Add `.veyq-card-interactive` only when the card itself is clickable.

Cards are appropriate for Memories, media, playlists, tracks and repeated contextual objects. Rows are better for administrative or dense management data.

Do not nest cards inside cards. Page sections should normally be unframed layout containers, with cards reserved for repeated items or focused panels.

Use `.veyq-empty` and `.veyq-loading` for empty and loading states. Empty states should be concise, useful and action-oriented. They should not read like marketing copy or a tutorial.

## Dialogs and Forms

Use existing PrimeVue dialog wrappers and sizing classes such as `.tenant-dialog`, `.share-dialog` and feature-specific dialog classes. Inside dialogs, use `.veyq-form`, `.veyq-form-grid`, `.veyq-form-copy`, `.veyq-dialog-action-row`, `.veyq-action-row` and `.veyq-action-group`.

Dialog rules:

- One focused task per dialog.
- Keep explanatory copy short and directly tied to the action.
- Put cancel/secondary actions on the left and submit/save/confirm/destructive actions on the right.
- Make privacy, sharing and destructive consequences explicit.
- Keep max heights mobile-safe and allow content to scroll when needed.
- Place validation and error messages near the affected control where possible.

Forms should use full-width inputs by default. Labels should be concise. Helper text uses muted color and should explain consequences, not repeat the label.

## Memory Product UI

Use `Memory` language for rich content objects. Use folder language only for organizational navigation. Do not reintroduce Album or Explorer naming, UI flows, routes, API concepts or database concepts.

Memory surfaces should prioritize:

- Cover media.
- Title and date range.
- Place or context metadata when available.
- Gallery media.
- Journal content.
- Maps, tracks and playlists.
- Child Memories.

Folders should remain visually secondary to Memories. A folder card or navigation row should help users find Memories, not compete with a Memory as a rich content object.

Public sharing UI must avoid implying public profiles, cross-Memory person discovery, social feeds, reactions or public social graph visibility.

## Accessibility and Responsiveness

Interactive elements need visible focus states, accessible names and predictable keyboard behavior. Icon-only actions require an `aria-label` or tooltip text that names the action.

Text must not overflow its container on mobile. Use existing responsive `.veyq-*` behavior before adding component-specific media queries.

Do not rely on hover-only affordances for essential actions. Touch and keyboard users must be able to discover and operate the same controls.

## Agent Checklist

Before finishing frontend or product-facing work:

- Used `.veyq-*` shared classes before adding local classes.
- Used existing `--app-*` tokens before raw colors.
- Checked light and dark mode implications.
- Used `Memory` product language and kept folders organizational.
- Avoided Album and Explorer concepts.
- Kept share and public surfaces privacy-preserving.
- Chose rows for management data and cards for repeated content objects.
- Added accessible labels for icon-only actions.
