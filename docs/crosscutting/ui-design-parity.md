# Cross-platform UI design parity

veyq runs on many surfaces — web, Apple (macOS/iOS/iPadOS), Android, later Windows & Linux. They share
**no UI code** (ADR 0010: shared *contracts*, not code), yet must feel like **one product**. This doc is
the durable statement of *what* is shared visually and *how* each surface realises it. It governs every
native UI CR (Apple cr-0015, Android cr-0016, the desktop heads next).

> Behavioural parity (which use-case exists per surface, and what a build proves) is the
> [`coverage/`](../coverage/) chain. This doc is the orthogonal **visual / interaction** axis.

## Principle

**One veyq design, native everywhere.** Every surface shares the same *design intent* — palette, layout
rhythm, component roles, flow, and product copy — but is implemented in that platform's **native toolkit
and idioms** (SwiftUI, Jetpack Compose, WinUI, GTK, Vue/CSS). We **reuse the design, not the widgets**:
never a cross-platform UI runtime, never a stretched phone layout on a tablet, never platform-foreign
controls. When a surface must differ (platform affordance, form factor), it differs **in-style** and the
difference is noted in that surface's styleguide.

The shipped **Apple** login/unlock (cr-0015) + the **web** client are the current visual reference; new
heads match them.

## Canonical role tokens

Use **semantic role tokens**, never raw colors, for application UI. Every role defines **light and dark**.
These are the source-of-truth values; each surface maps them to its own token mechanism (see the table).

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| `bg` | `#F7F8FB` | `#101418` | app background (full-bleed) |
| `surface` | `#FFFFFF` | `#171C22` | framed cards / panels |
| `surfaceMuted` | `#F4F6FB` | `#20262E` | inset fields, soft info boxes |
| `text` | `#111827` | `#EEF3F8` | primary foreground |
| `muted` | `#667085` | `#9AA7B5` | secondary text, helper/meta |
| `line` | `#DCE2EC` | `#333D49` | borders, separators |
| `accent` | `#5B4DF5` | `#9B8CFF` | brand mark, primary action, focus |
| `success` | `#0F766E` | `#2DD4BF` | unlocked/positive/confirm |
| `warning` | `#FACC15` | `#FACC15` | group/shared/context badges (never submit) |
| `info` | `#2563EB` | `#67E8F9` | non-critical helper cues |
| `danger` | `#B42318` | `#F87171` | errors, destructive actions |

Radii: **`radius` = 12** (cards), **`radiusSm` = 10** (fields, buttons, small chips). Raw colors are
allowed only for *rendered content* (media scrims, map/canvas), never for UI roles.

## Shared component contract

Each surface provides these abstract components (named here so the platform styleguides can map them);
the auth flow (connect → sign-in → home) is built only from these:

- **App background** — `bg`, full-bleed, ignores safe areas.
- **AuthCard** — branded container: veyq wordmark + lock glyph, a title, an optional subtitle, and slotted
  content. `surface` fill, `line` border, soft shadow, `radius`. **Width-capped and centred** (see
  Responsive) — never edge-to-edge on large screens.
- **Labeled field** — a role label (`text`, semibold) above a plain/secure input. `surfaceMuted` fill,
  `line` border, `radiusSm`. Optional **helper text** (`muted`) that explains a *consequence*, not the
  label (e.g. "Used with your password to unlock — never sent").
- **Primary button** — full-width, `accent` fill, white text, `radiusSm`; shows a **busy** state ("…") and
  a **disabled** state (reduced-opacity accent) while a mutation runs or the form is incomplete.
- **Secondary / text button** — `muted`, unfilled (e.g. "Use a different instance").
- **Message box** — inline status: error = `danger` text on a `danger`-tinted fill; info = `muted` on
  `surfaceMuted`. `radiusSm`.
- **Unlocked state** — lock-open glyph in a `success` tint, account + instance shown, and a one-line
  **operator-blind reassurance** ("Your keys are open on this device only…").

Product language is **Memory / Chapter** (DE *Kapitel*) — never Album/Explorer; nothing implying a social
graph (see [ui-web-styleguide §Product language](ui-web-styleguide.md)).

## Responsive rule (every surface)

The layout adapts by **size class**, not by OS:

- **Compact** (phone portrait): single column; the card fills the width with comfortable padding.
- **Regular** (tablet, desktop, large window): the card **caps at a readable width** (~440pt for the auth
  cards) and **centres**; the surrounding space stays background. **Not** a stretched phone layout.

Realise this with the platform's size-class API — SwiftUI `horizontalSizeClass` / a frame cap, Compose
`WindowSizeClass`, CSS `max-width`/container queries — never fixed pixel breakpoints copied across
platforms.

## Per-surface mapping

| Abstract | Web | Apple (SwiftUI) | Android (Compose) | Windows (WinUI) | Linux (GTK) |
| --- | --- | --- | --- | --- | --- |
| Tokens | `--app-*` (`web/src/style.css`) | `VeyqStyle` (`apple/App/VeyqStyle.swift`) | a Veyq Material3 theme (`:app`) | a Veyq `ResourceDictionary` | a libadwaita style provider |
| AuthCard | `.veyq-surface` | `AuthCard` | `@Composable AuthCard` | `UserControl` | `AdwClamp` + card |
| Labeled field | `.veyq-form` input | `VeyqField` | `VeyqField` composable | styled `TextBox` | `GtkEntry` row |
| Primary button | positive button | `PrimaryButton` | `PrimaryButton` composable | accent `Button` | suggested-action `GtkButton` |
| Message box | `.veyq-*` inline | `MessageBox` | `MessageBox` composable | `InfoBar`-style | inline `GtkLabel` box |
| Responsive | `max-width` | `horizontalSizeClass` / frame cap | `WindowSizeClass` | adaptive triggers | `AdwClamp` |

Per-surface specifics (platform affordances, gotchas, exact APIs):

- **Web** — [`ui-web-styleguide.md`](ui-web-styleguide.md)
- **Apple** — [`ui-apple-styleguide.md`](ui-apple-styleguide.md)
- **Android** — [`ui-android-styleguide.md`](ui-android-styleguide.md)
- Windows / Linux — to be added with their UI heads.

## Checklist before finishing native UI work

- Used **role tokens** (light + dark) from the table above — no raw colors for UI.
- Built the flow from the **shared component contract**; nothing invented that another surface lacks.
- **Width-capped + centred** on Regular size class; verified it isn't a stretched phone layout.
- Memory/Chapter language; operator-blind reassurance on the unlocked state.
- Matched the shipped Apple/web reference; any deliberate difference noted in the surface styleguide.
