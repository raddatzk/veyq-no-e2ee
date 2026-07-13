---
id: cr-0157
title: Web transient feedback — adopt PrimeVue Toast, add useToast/useUndo, migrate notices + undo bars (audit P3d)
status: done
satisfies: []
adrs: ["0039"]
invariants: []
---

## Goal

Final slice of audit finding #74 (**P3d**, web async primitives): the `useToast`/`useUndo` item. Per **ADR 0039**,
adopt **PrimeVue Toast** as the client's single transient-feedback channel — a deliberate, recorded deviation
from "reuse UI, never invent" (the inline undo bars were already a veyq invention, and consolidating ephemeral
feedback into one accessible channel beats two hand-rolled bars + scattered `notice` refs). Register the toast
infra, add a thin `useToast` + a generic `useUndo<T>` composable, then migrate **both** kinds of transient
feedback: the 5 inline success-`notice` sites → auto-dismissing toasts, and the 2 hand-rolled undo bars →
undo-toasts (a "Rückgängig" action, timer/restore owned by `useUndo`). Form validation + persistent load-error
states stay inline (ADR 0039 scopes this to success + undo feedback only).

## Context & constraints

Builds on the cr-0155 primitives' style (`shared/state/useAsyncData`/`useAsyncAction`). No new product concept
beyond ADR 0039; no contract or crypto change — `satisfies:` empty, `adrs: ["0039"]`.

### Infra + primitives

- **`main.ts`** — `.use(ToastService)`. **`App.vue`** — mount the global toast surface(s): a default
  `<Toast />` for success, plus a templated `<Toast group="undo">` whose `#message` slot renders the
  undo-toast's text + a "Rückgängig" action button (PrimeVue Toast needs a group + custom template to carry an
  action).
- **`shared/state/useToast.ts`** — a thin wrapper over PrimeVue's `useToast()` fixing app conventions:
  `success(summary)` (default `life`, auto-dismiss) and `showUndo({ summary, onUndo, life })` (targets the
  `undo` group, wires the action + an `onExpire` commit). i18n strings passed in by callers (keys already exist
  for the notices).
- **`shared/state/useUndo.ts`** — a generic `useUndo<T>({ timeoutMs, onExpire? })`: holds the pending payload
  `T | null`, arms a `setTimeout`, and exposes `arm(payload)`, `cancel()` (the user hit Undo), `expire()`, and
  `onBeforeUnmount` cleanup — the timer/ref/auto-dismiss skeleton both bars duplicate. The item-specific restore
  (tombstone lift / server restore / re-list) stays at each call site as the `onUndo`/`onExpire` callback.
- Both composables get co-located `*.test.ts` (happy-dom `harness()` pattern, fake timers for `useUndo`).

### Sites migrated

- **Success notices → `useToast().success(...)`** (retire the `notice`/`addNotice` ref + its inline
  `<Message severity="success">`): `ProfilePanel` (profile saved), `TenantMembersPanel` (invite sent / reissued
  / removed / revoked), `GroupMembersPanel` (invitation sent), `TenantTermsPanel` (terms saved),
  `TenantDetailPanel` (resend/reassign success). The `notice.value = ''` resets at handler starts drop out.
- **Undo bars → undo-toasts:** `MemoryBuckets.vue` (Site B, multi-id, `.bucket-undo-bar` markup + `undo`/
  `undoTimer` state) and `useMediaGalleryLive.ts` (Site A, single-id, `undo`/`undoTimer`, welded to
  `pendingDelete` tombstones) both drop their hand-rolled timer + bar and call `useToast().showUndo(...)`, with
  `useUndo` owning the timeout. `MemoryDetail.vue` loses the `.memory-undo-snackbar` markup + CSS; `MemoryBuckets`
  loses `.bucket-undo-bar` markup + CSS. The delete/restore/tombstone business logic is unchanged.

### Out of scope

- Form **validation errors** and persistent **load-error** `<Message>` states — stay inline (ADR 0039).
- The static dialog/flow success `<Message>` (InviteMemberDialog/CreateTenantDialog copy-link view,
  TenantJoinView/TermsAcceptView completion) — those are flow-terminal states, not transient acks; unchanged.

### Verify

- `cd web && npm run check` green, incl. `useToast` + `useUndo` unit tests.
- `python3 scripts/validate_coverage.py` green (no `satisfies:`).
- Live smoke on `veyq.localhost:8081`: saving the profile shows a success toast (auto-dismisses, form-inputs
  untouched); deleting media shows an undo-toast whose "Rückgängig" restores the item and whose expiry commits
  the delete; a bucket multi-delete shows the same; only ONE `<Toast>` region exists (no stacking bugs). Console
  clean, light + dark.

## Phases

- [x] 1 web — infra + primitives: `ToastService` in `main.ts`, the two `<Toast>` surfaces in `App.vue`,
      `useToast` + `useUndo` (+ unit tests) · Done when: `npm run check` green; a throwaway `success()` call
      renders a toast in the live app.
- [x] 2 web — migrate the 2 undo bars (`MemoryBuckets`, `useMediaGalleryLive`/`MemoryDetail`) to undo-toasts via
      `useUndo` + `useToast`; delete the hand-rolled bars + their CSS · Done when: `npm run check` green + live
      undo smoke (media + buckets) — restore works, expiry commits.
- [x] 3 web — migrate the 5 success-`notice` sites to `useToast().success()`; remove the `notice` refs + inline
      success `<Message>` · Done when: `npm run check` green + a save/invite shows a success toast.

## Definition of done

`ToastService` + one global toast surface exist; `useToast` + `useUndo` exist with unit tests; the 5 success
notices and the 2 undo bars are migrated (their inline `notice`/bar markup + CSS removed); form validation and
load-error states remain inline per ADR 0039; `npm run check` + `python3 scripts/validate_coverage.py` green;
live smoke (success toast + both undo flows, light + dark) passes.

## Retrospective

Delivered as scoped. `ToastService` + two `<Toast>` surfaces (default success, top-right; templated `undo` group,
bottom-center, via the `#container` slot carrying a "Rückgängig" action + close) live in `App.vue`;
`useToast` (`success`/`showUndo`/`dismissUndo`) and generic `useUndo<T>` (`arm`/`cancel`/`expire` + unmount-commit)
land in `shared/state/` with co-located tests. All 5 success notices and both undo bars migrated (their inline
`notice`/bar markup + CSS removed); form-validation + load-error `<Message>` states stayed inline per ADR 0039.

Two implementation notes worth recording:
- **The undo window commits nothing.** Both bars soft-delete **eagerly** (the server trash write happens at delete
  time, before the window opens), so `useUndo`'s `onExpire` is unused at both call sites — expiry just lets the
  trash stand; only the "Rückgängig" restore (`useUndo.cancel()` → `restoreMedia`) is a real action. The
  `onExpire` hook stays in the primitive for the general case. `showUndo`'s `life` (visual auto-dismiss) is kept in
  sync with the `useUndo` `timeoutMs` (6 s media / 8 s buckets); the failure path calls `toast.dismissUndo()`.
- **Undo-toast look.** The undo-toast's `#container` is styled with the app surface tokens (`--app-surface` /
  `--app-line` / `--app-shadow-md` / `--app-radius`, action in `--app-accent`) so it reads as one family with the
  success toast + the rest of the UI in both themes — the initial dark-pill reuse of the old lightbox snackbar
  clashed with the theme and was dropped.
- **Test infra.** Components that call `useToast` need the PrimeVue toast provider, so a new global vitest setup
  (`src/test/setup.ts`, wired via `test.setupFiles` in `vite.config.ts` — import switched to `vitest/config`)
  pushes `ToastService` into `@vue/test-utils`' global config. Undo-bar component/composable tests that used to
  assert on the inline bar markup now stub the shared `useToast` and drive the captured `onUndo`.

Verified: `npm run check` + `validate_coverage.py` green. Live smoke on `veyq.localhost:8081` (light + dark,
console clean): profile-save success toast auto-dismisses with form inputs untouched; a lightbox media delete
shows the undo-toast and "Rückgängig" restores the item (confirmed via trash + gallery after reload); a Beiträge
multi-delete shows the correctly-pluralized "2 Medien" undo-toast and its expiry lets the delete stand; exactly one
toast region, no stacking.
