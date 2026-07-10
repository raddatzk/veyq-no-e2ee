---
id: cr-0119
title: Mutable share role — change a share's grant in place (VIEWER ↔ CONTRIBUTOR)
status: done
satisfies: [uc.sharing.share-contributor]
adrs: ["0036"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a curator **change an existing share's role in place** — promote a **VIEWER** link to **CONTRIBUTOR** (view +
add photos) or demote it back — **without minting a new link**. The crypto investigation confirmed the three roles
are **cryptographically identical** (each share is one `symWrap(K_share, K_mem)`; `role` is a plaintext column the
server enforces per request), so a role change is a **pure server-side authorization change** — no re-wrap, no new
`K_share`, the `/s/{shareId}#k=…` link stays valid ([ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md)
extends `updateShare` with `role`).

**Scope: VIEWER ↔ CONTRIBUTOR only.** UPLOADER is **excluded** by design — a write-only drop-box's "cannot see"
promise can't be honestly flipped on links already handed out, and the uploader is its own separate memory
action/dialog anyway (cr-0116). So the affordance falls out cleanly: the role switcher already lives only in the
**normal** create/edit dialog (never the uploader variant), so it naturally offers only VIEWER/CONTRIBUTOR.

## Context & constraints

- **Operator-blind (invariant) — unaffected.** `role` is already server-visible plaintext operational metadata
  (the server enforces `canView`/`canUpload` per request). Changing it swaps one enum value for another; no key
  crosses the boundary, nothing is re-encrypted, the wrap/`sealedName`/`sealedShareKey`/`K_share` are untouched.
- **Server-enforced, not advisory.** The server re-reads the row's current role on every guest request, so the new
  grant takes effect **immediately** on the next resolve/upload — a demote-to-VIEWER 403s new uploads at once, a
  promote-to-CONTRIBUTOR enables them at once.
- **UPLOADER is a hard rejection, both directions.** `updateShare` **422-rejects** (`invalid_role_change`) any
  request that (a) sets `role = UPLOADER`, or (b) targets a share whose **current** role is UPLOADER. This keeps
  the write-only boundary honest (you never silently turn a distributed drop-box link into a read link, or vice
  versa) and sidesteps the `sharing.uploader-links` capability question entirely (create still gates it).
- **Honesty note (VIEWER ↔ CONTRIBUTOR, benign).** Demote CONTRIBUTOR→VIEWER stops **new** uploads immediately;
  photos already contributed stay in the Memory (they are the owner's content). Promote VIEWER→CONTRIBUTOR only
  enables uploads. Neither leaks anything a link-holder didn't already have (`K_mem` via the wrap is unchanged).
- **Owner-gated.** Only the share's `owner_account` may edit it — a share not owned by the caller stays a
  non-enumerable `404`, exactly like the note/name edit and revoke.
- **`role` is optional on the PATCH.** When omitted the grant is unchanged (a plain note/name edit — the uploader
  dialog's note edit sends **no** role, so it is never rejected). Note + name stay the full-replace pair; `role` is
  an optional grant change layered on the same owner-gated op.
- **Verify:** backend `./gradlew check` (a slice test: promote VIEWER→CONTRIBUTOR in place enables the guest
  contribution + keeps the link/wrap; role=UPLOADER and an UPLOADER-share role change are 422); web `cd web && npm
  run check`; the three trust-chain guards; a live smoke (edit a viewer share → switch to "Ansehen + Fotos" →
  save → same link now accepts a contribution).

## Phases

- [x] 1 **contracts** — `UpdateShareRequest += role` (optional `ShareRole`); `updateShare` responses gain
  **`422 invalid_role_change`**. Regen both clients. · **Done:** the generated web `UpdateShareRequest` carries
  `role?: ShareRole`; backend codegen green.
- [x] 2 **backend** — `SharingStore.updateShare` takes `role: String?` and returns a 3-way `UpdateResult`
  (`DONE`/`NOT_FOUND`/`INVALID_ROLE`): when `role` is present it validates `role ∈ {VIEWER, CONTRIBUTOR}` **and** the
  share's current role `∈ {VIEWER, CONTRIBUTOR}`, else `INVALID_ROLE`; `SharingHandler`/`SharingResource` map
  `InvalidRole → 422`. · **Done:** `./gradlew check` green (**288 tests**) — `ShareNoteSliceTest` +3: promote
  VIEWER→CONTRIBUTOR in place keeps the link (same `wrap`) + enables the anonymous contribute; `role=UPLOADER` and
  a role change on an UPLOADER share are both 422.
- [x] 3 **http** — the existing PATCH auth boundary (`sharing.http` case 7, no-bearer → 401) already guards the
  curator op; the role-change **semantics** are backend-slice-proven (an authed role-change happy-path is a
  fresh-instance flow, out of scope for the boundary file). No new http case.
- [x] 4 **web** — `share.ts updateShare` gains an optional `role`, sent only when provided; `ShareCreateDialog`
  shows the VIEWER/CONTRIBUTOR switcher on **edit** too in the normal flow (`showRoleSwitcher = !uploader`),
  seeded to the current role, and passes it to `updateShare` (never for the uploader variant). · **Done:**
  `npm run check` green (**552 tests** — +1 edit-mode-switcher test).
- [x] 5 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries the optional
  `role` on the update after regen; the native share-management UI is each platform's own later CR.

## Status note (implemented 2026-07-10)

Shipped **contracts → backend → web**, all **automated** verification green: backend `./gradlew check` = **288
tests, 0 failures** (incl. the 3 new role-change slice tests), web `npm run check` = **552 tests** + build, and the
**five trust-chain guards** green (coverage picks up the new `role-mutable-in-place` criterion). Refined state
edited in place: [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md) (role is the first
grant-affecting `updateShare` field), [uc.sharing.share-contributor](../docs/features/sharing/use-cases/uc.sharing.share-contributor.md)
(alternate flow A3 + the coverage criterion).

**Live browser smoke — PASSED (2026-07-10, after the backend restart).** Editing a share now shows the
VIEWER/CONTRIBUTOR switcher; switching a VIEWER share ("Passwort-Test") to CONTRIBUTOR → save → the manage list
reflects the new grant **in place** (same link, no new mint), and an unrelated field (its password) was **kept**
(the tri-state "untouched" path). The switcher was live-verified full-width. (The interim "pending a backend
restart" state was due to the dev agent not re-running `openApiGenerateSpecs` on a contract change — a clean restart
cleared it, as expected.)
