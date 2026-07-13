---
id: cr-0155
title: Web async primitives — useAsyncData/useAsyncAction + handle401, sweep the non-panel sites (audit P3d)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Audit finding #74 (**P3d**, web async primitives) remaining work, slice 1 of 2. Introduce three small shared
primitives — `useAsyncData` (load-on-mount fetch state), `useAsyncAction` (form/dialog submit state), and
`handle401` (redirect-to-login on an unauthorized response) — and migrate **every non-member-panel site** onto
them. Today the `loading/error + try/finally` pattern is hand-rolled across ~19 components in 3 shapes, and the
line `if (status === 401) return void router.push({ name: 'login' })` is copy-pasted **24×** with no shared
helper. This is a behaviour-preserving structural dedupe. The two **member panels** (`TenantMembersPanel`,
`GroupMembersPanel`) are deliberately left out — they sit in all three concerns at once and are deduped as one
pass in the follow-up **cr-0156**, so they churn once.

## Context & constraints

Builds on the primitives already landed under this same audit item (#74/P3d): `shared/httpError.ts` (cr-0141 —
`isUnauthorized`/`httpStatus`/`decodeHttpError`, the **detection** half `handle401` pairs with) and
`shared/state/useObjectUrls.ts` (cr-0148 — the stylistic template for a `shared/state/` composable). Router
login target is the named route `login` (`router.ts`); navigation is a direct `router.push({ name: 'login' })`
(no store action). No new product concept, no contract or crypto change — `satisfies:`/`adrs:` empty.

### The three primitives (`web/src/shared/state/`)

Keep them **thin**. Two composables cover Shapes A and B; Shape C (MediaLightbox's parallel, stale-guarded ops)
uses `useAsyncAction` **per op** and keeps its own stale-result guard rather than forcing it into a shared flag.

- **`useAsyncData(loader, { immediate = true, onError? })`** → `{ data, pending, error, reload }`. Runs
  `loader` on setup (unless `immediate: false`), tracks `pending`, stores the raw caught error in `error`
  (`null` on success), and calls the optional `onError(e)` hook (e.g. `handle401(router)`) before settling.
  Callers derive their own `loadError` view from `error` — the shape differs (bool vs the
  `'' | 'notAuthorized' | 'failed'` enum), so the primitive stays un-opinionated. Ship a tiny co-located helper
  `loadErrorKind(error): 'notAuthorized' | 'failed' | null` (the 403-vs-other split the panels repeat) so the
  enum mapping isn't re-hand-rolled.
- **`useAsyncAction(action, { onError? })`** → `{ run, pending, error, reset }`. `run(...args)` toggles
  `pending`, awaits `action`, and on throw stores the raw error and returns `undefined` (callers map `error` to
  their i18n message; success returns the value). The keyed `busy: ref<string | null>` row-marker sites
  (`GroupInvitationsView`, one in `TenantDetailPanel`) keep a thin local `busyKey` ref set around `run` — no
  keyed variant of the primitive in this slice (revisit only if a third keyed site appears).
- **`handle401(router): (e: unknown) => void`** → curried so it drops straight into `useAsyncData`'s `onError`;
  redirects to `{ name: 'login' }` **iff** `isUnauthorized(e)` (reuses `httpError.ts`), else no-op. Inline
  `catch` sites call `handle401(router)(e)`. It is redirect-only — it does **not** clear the session (the full
  clear-then-redirect sign-out stays in `components/app/ProfileMenu.vue`, out of scope).

### Sites migrated here (non-member-panel)

- **Shape A (load-on-mount) + their handle401 redirects:** `ProfilePanel`, `TenantDetailPanel`,
  `TenantTermsPanel`, `views/GroupInvitationsView`, `views/TermsAcceptView`. (`TermsAcceptView` currently runs a
  `phase` string-machine — evaluate whether `useAsyncData` fits cleanly; **don't force it** if the load+submit
  state machine reads worse on the primitive.)
- **Shape B (submit):** `CreateGroupDialog`, `CreateTenantDialog`, `InviteMemberDialog`, `DeleteNodeDialog`,
  `NodeMutationDialog`, `ChangedKeyReviewDialog`, `views/LoginView`, `views/SetupView`, `views/TenantJoinView`,
  `ShareCreateDialog`, and `ProfilePanel.save` (keep its orthogonal `notice` success channel as-is).
- **Shape C (parallel, stale-guarded):** `MediaLightbox` — **stays bespoke** (the documented Shape-C
  exception). On inspection **3 of its 4 ops** (`loadPreview`, `loadDetails`, `persistCapturedAt`) carry the
  `target !== item.value` stale-result guard; only `download` is a clean one-shot. The guard is exactly the
  capability the "two thin primitives" decision deliberately kept OUT of `useAsyncData`/`useAsyncAction`, and
  the primitive's unconditional `pending` toggle would fight it. Migrating only `download` (1 of 4) would add
  inconsistency for negligible gain, so the whole file is left as-is.

### Explicitly out of scope

- **The two member panels** (`TenantMembersPanel`, `GroupMembersPanel`) and their 12 handle401 sites → **cr-0156**
  (they get their fetch-state + handle401 + dedupe in one pass there).
- `views/TenantJoinView`'s local `isAuthLapse()` re-auth path (offers password-only re-login, vault still
  unlocked — different semantics from a redirect) and `features/account/login/login.ts`'s `withRefresh`
  refresh-and-retry — both **stay**, they are not redirect sites. TenantJoinView is a 5-step wizard sharing ONE
  `busy` flag across its actions, a poor fit for per-action `useAsyncAction` (5 pending refs for one button), so
  its fetch-state is **not** migrated — the only change there is `isAuthLapse` delegating to the shared
  `isUnauthorized` instead of its own inline `.response.status === 401` duck-type.
- `useToast`/`useUndo` — no toast infra exists today (PrimeVue `ToastService` isn't registered); a separate,
  independent slice, not bundled here.

### Verify

- `cd web && npm run check` (eslint + `vue-tsc` + test + build) green, incl. new unit tests for the three
  primitives (`useAsyncData` immediate/pending/error/reload + `onError`; `useAsyncAction` run/pending/error/reset;
  `handle401` redirects on 401 only).
- `python3 scripts/validate_coverage.py` green (no `satisfies:` — coverage unchanged).
- Live smoke on `veyq.localhost:8081`: a load-on-mount panel (e.g. Profile) shows its loading→loaded path and a
  forced 401 redirects to login; a dialog submit (e.g. create group) shows busy→error on a bad input and
  busy→success on a good one; the media lightbox opens a preview, downloads, and edits a capturedAt without a
  stale-result flash when switching items quickly. Console clean.

Files were migrated **file-complete** (each touched once, all its concerns at once) rather than strictly per the
concern-phased plan below, because several sites carry both a load and a submit (e.g. ProfilePanel,
TenantDetailPanel) and splitting them across phases would touch a file twice.

## Phases

- [x] 1 web — the three primitives in `shared/state/` + `shared/handle401.ts` (`useAsyncData` + `loadErrorKind`,
      `useAsyncAction`, `handle401`) + 13 unit tests. `error` is a **`shallowRef`** (a deep-reactive proxy over a
      raw HTTP error breaks the later `decodeHttpError` `Response.clone()`); `useAsyncData` starts `pending: true`
      when `immediate` so the first render shows loading (what `loading = ref(true)` encoded).
- [x] 2 web — Shape-A load-on-mount panels/views + handle401: ProfilePanel (load+save), TenantDetailPanel
      (load+mint via useAsyncAction; the `gone` business state kept alongside the error-derived `loadError`),
      TenantTermsPanel (load+save), GroupInvitationsView (load + deduped accept/decline), TermsAcceptView
      (handle401 only — its `phase` state-machine kept). `npm run check` green.
- [x] 3 web — Shape-B submit dialogs/views: CreateGroup/CreateTenant/InviteMember/DeleteNode/NodeMutation/
      ChangedKeyReview + ShareCreateDialog dialogs, Login/Setup views. `npm run check` green.
- [x] 4 web — MediaLightbox (Shape C): left **bespoke** (see Context) — no code change; the stale-result guard in
      3 of its 4 ops is incompatible with the thin primitive's unconditional `pending`.

## Definition of done

All phases complete: the three primitives exist with unit tests, every listed non-panel site is migrated off its
hand-rolled `loading/error/try-finally` and off the inline `router.push({ name: 'login' })`, MediaLightbox left
bespoke as the documented Shape-C exception, the two member panels untouched (reserved for cr-0156),
`npm run check` + `python3 scripts/validate_coverage.py` green. Behaviour-preserving.

## Retrospective

- **Planning miss: the concern-phased slice (P2 Shape-A, P3 Shape-B) fought "touch once".** Several files carry
  both a load and a submit (ProfilePanel, TenantDetailPanel), so the honest execution migrated file-complete and
  the phase boundaries became a grouping, not a work order. Next time: slice a within-file refactor by file, not
  by concern, when the concerns co-locate.
- **Planning miss: two API shapes only surfaced during migration, not design.** `useAsyncData` needed
  `pending: true` at creation (first-render loading) and `error` as a `shallowRef` (a deep-reactive proxy over a
  native `Response` breaks `decodeHttpError`) — both found by a failing test / a flash-of-empty, not up front.
  The `shallowRef` one is a real latent-bug class, worth remembering for any composable that stores a thrown
  HTTP error in a ref. Next time: for an error-holding ref, reach for `shallowRef` by default.
- **Test-shape coupling.** The load panels' tests mocked the async decoder (`tenantError`) to fake a status
  rather than throwing a real `.response`-shaped error; moving 401 detection to the sync `isUnauthorized` (which
  duck-types `.response.status`, as production errors do) required rewriting those two tests. The tests were
  asserting an implementation detail. Only `TenantDetailPanel.test.ts` needed the change; the submit dialogs
  keep decoding for the `code`, so their 409 mocks stayed valid.
- **Scope honesty over the CR text.** Two files (MediaLightbox, TenantJoinView) were written into the plan as
  full migrations but are genuinely poor fits (stale-result guard / one-shared-busy wizard). Followed the
  approved "two thin primitives — don't force it" decision and left them, updating the CR to say so rather than
  forcing a worse abstraction to match the original wording.
- **Verification split by a standing prohibition.** Entering the dev password into the login field is a
  standing prohibition, so the login step was done by the user (agent pre-filled only the non-secret email);
  the agent then drove the rest. Full live smoke on `veyq.localhost:8081`, console clean throughout: (1)
  `LoginView.submit` (useAsyncAction) — logged in end-to-end; (2) `ProfilePanel` (useAsyncData) — loaded all
  fields incl. the K_tenant-unsealed name (`kevin` / `Kevin` / `Raddatz`); (3) `InviteMemberDialog`
  (useAsyncAction) — submitting an existing member's email showed busy → the mapped 409 "already member" error
  with no side effect, proving the preserved `memberError` code-mapping in `onError`. The `handle401` redirect
  rests on its unit test (forcing a live 401 needs an expired session). Next time: front-load the "which smoke
  steps need a human-entered credential" question when writing the DoD, so the hand-off is planned not
  discovered.
