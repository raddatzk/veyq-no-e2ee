# ADR 0039 — Transient feedback via a floating Toast

- Status: Accepted
- Date: 2026-07-13

## Context

The web client's user feedback is entirely **inline** today:

- **Success confirmations** — a `const notice = ref('')` rendered as `<Message severity="success" :closable="false">`
  next to the form (ProfilePanel, TenantMembersPanel, GroupMembersPanel, TenantTermsPanel, TenantDetailPanel).
  Persistent, form-adjacent, cleared on the *next* action rather than by a timer.
- **Undo affordances** — two hand-rolled undo bars: the gallery/lightbox delete-undo (cr-0110, in
  `useMediaGalleryLive`) and the buckets multi-delete-undo (cr-0122, in `MemoryBuckets.vue`). Each is a
  transient bar with an "Undo" button and a `setTimeout` auto-dismiss; the timer/ref/cleanup skeleton is
  duplicated between them.

The project rule (`CLAUDE.md`, `docs/ai/README.md`) is **reuse the legacy design, never invent new UI**; the web
design source is `legacy/frontend-noe2ee/`. That source has **no toast, snackbar, or undo** — its feedback
paradigm is inline `<Message>` only. The undo bars are therefore **already a veyq invention** layered on top of
legacy, not a ported pattern.

Audit finding #74 (P3d) flagged "add `useToast`/`useUndo` (no toast infra today — use PrimeVue Toast)". Adopting
a floating Toast is genuinely **new UI with no legacy precedent**, so it is in tension with the reuse rule and
must be a deliberate, recorded decision rather than a silent "reuse".

## Decision

Adopt **PrimeVue Toast** as the client's single **transient-feedback channel**, and route *both* kinds of
transient feedback through it:

- Register `ToastService` (`main.ts`) and mount **one** global `<Toast />` (`App.vue`), wrapped by a thin
  **`useToast`** composable that fixes the app conventions (severity, i18n summary/detail, default `life`).
- **Success confirmations** (the `notice` refs) become **auto-dismissing toasts** — the inline
  `<Message severity="success">` + `notice` ref pattern is retired at those sites.
- **Undo affordances** become **undo-toasts**: a toast carrying a "Rückgängig / Undo" action, its
  timeout + pending-restore managed by a generic **`useUndo<T>`** composable (the timer/ref/auto-dismiss/
  `onBeforeUnmount` skeleton the two bars duplicated); the item-specific restore stays at each call site.

This **deliberately overrides "reuse UI, never invent" for transient feedback only.** The justification: the
inline undo bars were already a veyq invention (not legacy), so there is no legacy paradigm being displaced for
undo; consolidating all ephemeral feedback into one consistent, accessible channel (PrimeVue Toast ships the
`role`/aria + focus semantics) is cleaner than maintaining two hand-rolled bars plus scattered `notice` refs.
Form-level *validation errors* and persistent *load-error* states stay inline — this decision covers success +
undo (ephemeral) feedback, not error states pinned to a form.

## Consequences

- **New paradigm cost.** Confirmation moves off the form and becomes ephemeral: a "saved" / "invited"
  acknowledgement is no longer pinned beside the control that produced it. Accepted as the price of one
  consistent feedback channel. (This is the main thing given up versus the status quo.)
- **One global surface.** `ToastService` in `main.ts` + a single `<Toast />` in `App.vue`; `useToast` +
  `useUndo` land in `shared/state/`, matching the cr-0155 (`useAsyncData`/`useAsyncAction`) primitive style.
- **Rejected — inline-only + `useUndo` dedup.** Keep all feedback inline, extract only `useUndo` to dedupe the
  two bars. Most reuse-faithful and lowest-risk, but leaves the scattered `notice` refs and two rendering paths;
  rejected in favour of consolidating feedback into one channel.
- **Rejected — undo-toasts only, notices stay inline.** A middle path (Toast gains a real consumer while
  form confirmations stay pinned). Rejected in favour of full consolidation for a single consistent paradigm.
- Implemented by **cr-0157**.
