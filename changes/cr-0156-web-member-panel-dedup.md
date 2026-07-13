---
id: cr-0156
title: Web member panels — extract useMemberDirectory + a shared member-row, dedupe Tenant/Group (audit P3d)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Audit finding #74 (**P3d**, web async primitives) remaining work, slice 2 of 2. Dedupe the two member panels —
`TenantMembersPanel` (341 LOC) and `GroupMembersPanel` (559 LOC) — which share a byte-identical load/trust/review
skeleton and member-list markup (Group's own comment says "Markup mirrors TenantMembersPanel") but were built by
copy. Extract the shared half into a `useMemberDirectory` composable + a shared presentational member-row
component, leaving two thin feature wrappers for the halves that genuinely diverge. Both panels are migrated onto
the cr-0155 primitives in this same pass, so each panel — which sits in all three P3d concerns (fetch-state,
handle401, dedupe) — is touched exactly once.

## Context & constraints

**Depends on cr-0155** (the `useAsyncData`/`useAsyncAction`/`handle401` primitives + `loadErrorKind` must exist).
Shared data layer already in place: `features/spaces/members.ts` (`memberError`, the member/trust queries). No new
product concept, contract, or crypto change — `satisfies:`/`adrs:` empty.

### Shared → `useMemberDirectory` + shared row

- **`features/spaces/useMemberDirectory.ts`** owns the duplicated skeleton: the `members` + `isOwner` + `trust`
  state, the byte-identical `refreshTrust()` and `openReview()`/`reviewTarget`/`reviewOpen`, the load path (on
  `useAsyncData` from cr-0155, `onError: handle401`), and `loadError` via `loadErrorKind` (the shared
  403-vs-other split). It takes a single `loadBody: () => Promise<{ rows, isOwner }>` — each panel's load fetches
  its rows and resolves ownership panel-specifically (Tenant reads the **active** space + decrypts K_tenant
  names + reads the owner-only invite list; Group is addressed by `scopeId`, derives ownership from the directory
  and reads the lifecycle state) — and the composable sets `members`/`isOwner` + refreshes trust. It also exposes
  `on401` so each panel's action handlers reuse the same redirect helper. Group re-loads on `watch(scopeId)` via
  the exposed `reload`.
- **Shared member-row component — evaluated and skipped.** The row markup diverges too much to share without a
  slot-heavy shell: the title (Tenant `Full Name || @user` vs Group `@user`), subtitle (Tenant
  `@user · email · role` vs Group `role`), status badge (Group adds a `pendingConsent` state), and the entire
  action column all differ. The only byte-identical fragment is the changed-key badge + review button (~6 lines,
  and they sit in **two different DOM parents** — meta vs actions — so one component can't own both). Extracting
  that alone is negative-value indirection, so the row stays per-panel; the real dedup is the composable.

### Kept feature-specific (two thin wrappers — do NOT unify)

- **Add-member flow.** Tenant = invite-by-email (`InviteMemberDialog`) + reissue/revoke pending-invite links.
  Group = add existing member by username inline, with the consent / `resolveGroupMember` /
  `wrapGroupNameKeyForInvitee` / `IdentityChangedError` re-pin path.
- **Identity axis.** Tenant decrypts sealed real names (`K_tenant`); Group is `@username`-only.
- **Role + lifecycle.** Group's `setMemberRole` (promote/demote co-owner), `removeGroupMember` +
  `rotateGroupForRemoval` (key rotation), `renameGroup`, and the delete/restore/`purgeAfter` danger-zone are
  Group-only — that is most of its extra ~220 LOC. Tenant's `removeTenantMember` is a single server cascade, no
  rotation. These stay in their respective wrappers.

### Verify

- `cd web && npm run check` green.
- `python3 scripts/validate_coverage.py` green (no `satisfies:`).
- Live smoke on `veyq.localhost:8081`: the Tenant members panel and a Group members panel both load, render rows,
  and run a key-change review through the shared row; Group's role-change + remove (with rotation) and Tenant's
  invite-by-email still work unchanged; a forced 401 on either redirects to login. Console clean.

## Phases

- [x] 1 web — `useMemberDirectory` composable (on the cr-0155 primitives) + 5 unit tests. Shared member-row
      component evaluated and skipped (see Context — rows diverge too much).
- [x] 2 web — rebase `TenantMembersPanel` onto the composable, keeping its invite/name-decrypt half; its action
      handlers' 401 redirects now go through the exposed `on401`. `npm run check` green.
- [x] 3 web — rebase `GroupMembersPanel` onto the composable, keeping its add/role/rotation/rename/lifecycle
      half; its 7 action handlers' identical `{status,code}` + 401-redirect lines collapse onto `on401` +
      `isUnauthorized` (keeping `memberError` for the `code`). `npm run check` green.

## Definition of done

`useMemberDirectory` exists and is consumed by both panels; the duplicated members/isOwner/trust/review/load
skeleton lives in one place; the divergent add/identity/role/lifecycle halves stay in two thin wrappers; both
panels are on the cr-0155 primitives (fetch-state + handle401) and were touched once; `npm run check` +
`python3 scripts/validate_coverage.py` green; live smoke passed. Behaviour-preserving.

## Retrospective

- **Planning miss: the "shared member-row component" was in the plan (and the audit note) but didn't survive
  contact with the markup.** A close read shows the rows diverge on title, subtitle, status badge, and the whole
  action column; only a ~6-line changed-key fragment is identical, and it lives in two DOM parents. Extracting a
  slot-heavy shell would have been worse than the duplication. Skipped it with justification — same pattern as
  cr-0155's MediaLightbox. Next time: sanity-check a "shared component" claim against the actual template
  divergence during refinement, not just the prose "markup mirrors X".
- **The composable API landed clean on `loadBody -> {rows, isOwner}`.** Returning ownership (rather than a
  panel-owned ref the closure sets) let the composable own `isOwner` too and dodged a chicken-and-egg where the
  panel's load closure would need a ref the composable hadn't returned yet. Worth remembering for the next
  "extract the shared load" refactor.
- **Group's seven action handlers deduped cleanly** because the `{status, code}` + 401-redirect two-liner was
  byte-identical across all of them — one `replace_all` did it. The tell that a composable/helper is the right
  call: the same lines copy-pasted N times with only the tail differing.
- **Verification went smoothly this time** — the logged-in session from cr-0155 was still live, so the smoke ran
  end-to-end with no credential hand-off: both panels loaded via the composable (Tenant with K_tenant names,
  Group with role-derivation + lifecycle), and the Group add-member error path surfaced the mapped
  `member_not_found` with no mutation. Console clean throughout. Nothing went wrong worth flagging here.
