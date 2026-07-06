---
id: cr-0097
title: Rename a group — re-seal the name under the current epoch (forward-secret, owner-gated)
status: done               # planned → in-progress → done (kept as a record)
satisfies: [uc.spaces.rename-group]
adrs: ["0027", "0024", "0014"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **GroupOwner rename a group** — the write counterpart of the name-read cr-0096 shipped. The client re-seals
the new name under `K_name` derived from the scope's **current** content-key epoch and bumps the scope's
**`nameEpoch`** to it ([ADR 0027](../docs/decisions/0027-group-invite-name-shared-key.md)), so the rename is
**forward-secret**: a member removed at an earlier epoch cannot derive the new `K_name` and never reads the
post-removal name. **Name-only** — no `K_SC` rotation, no re-grant. Owner-gated; the server rejects a stale
`nameEpoch` (a concurrent rotation) and a rename on a pending-deletion group. Web-first (native later). **No new
crypto** — reuses cr-0096's `K_name` derivation + the `node-name` seal, so **no vector changes**.

## Context & constraints

- Durable truth: [uc.spaces.rename-group](../docs/features/spaces/use-cases/uc.spaces.rename-group.md) (the flow +
  acceptance), [ADR 0027](../docs/decisions/0027-group-invite-name-shared-key.md) (the rename-under-current-epoch
  mechanism the `nameEpoch` index enables), [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (epochs + the
  per-name epoch index), [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §4 (owner-gate).
- Invariant at risk: **operator-blind.** The rename overwrites the already-opaque `encrypted_name` (a key change the
  blind server is agnostic to) + bumps the small `name_epoch` integer. No key, no plaintext name reaches the server.
- Builds on **cr-0096** (the `nameEpoch` column, the `K_name` derivation, the member name-open path) — all
  unchanged here; this adds only the **write** path. The rename-between-invite-and-accept fail-safe
  (invitee preview → "unnamed") is already handled by cr-0096; this CR only realizes it.
- **Forward-secrecy depends on sealing under the *current* epoch.** The server enforces `nameEpoch == currentEpoch`
  (`409 stale_epoch`) so a stale-owner / concurrent-rotation rename can't weaken FS; the client refetches + retries.

## Phases

- [x] 1 contracts — API: add `RenameGroupRequest { encryptedName, nameEpoch }` (both opaque; `encryptedName`
  base64 `@NotBlank`/`@Size`/`@Pattern` as `CreateGroupRequest`, `nameEpoch` integer `minimum: 0`) + the endpoint
  **`POST /api/scopes/{scopeId}/name`** → `204`, errors `403 not_scope_owner`, `404 scope_not_found`,
  `409 stale_epoch`, `409 scope_pending_deletion`, `400` (blank/oversized). No crypto-vector changes (reuses
  cr-0096's `K_name`). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — `ScopesStore.renameGroup(scopeId, encryptedName, nameEpoch)` updates `encrypted_name` +
  `name_epoch` **only when** `nameEpoch == currentEpoch` and the group is active; a `RenameGroupOutcome`
  (`Ok` / `NotOwner` / `ScopeNotFound` / `StaleEpoch` / `PendingDeletion`) in the handler (owner-gate + GROUP-only +
  not pending-deletion); the resource maps them. Slice test: owner rename updates the row + bumps `nameEpoch`;
  non-owner → 403; `nameEpoch != currentEpoch` → 409; pending-deletion → 409; a **forward-secrecy** assertion
  (a rename after a rotation seals under the new epoch, unreadable at the old). · Done when: `./gradlew test` green.
- [x] 3 http — add the rename boundary to `http/spaces/scopes.http` with response-handler assertions (the
  owner-gated happy path is auth-checked; the crypto happy-path + guards are slice-covered). · Done when: the
  `.http` assertions pass against a seeded instance (parity surface).
- [x] 4 web — a **rename** action in the group settings (`GroupMembersPanel` / the group settings hub): open the
  scope → derive `K_name` for `currentEpoch` (the write key) → `sealName(K_name, newName)` → `POST …/name
  { encryptedName, nameEpoch: currentEpoch }`; on `409 stale_epoch` refetch the scope + retry once; refresh the
  switcher label. Reuse the existing rename UI/UX pattern (node rename), never invent new UI. Unit test: the rename
  seals under the current epoch (round-trips to the new name under `K_name_current`) + the stale-retry path.
  · Done when: `npm run check` green.

## Definition of done

A GroupOwner renames a group; every current member sees the new name on next `listMyScopes`; a member removed
**before** the rename cannot read the new name (only the old one they already held). A rename is name-only (no
`K_SC` rotation, no re-grant), owner-gated, rejected on a stale `nameEpoch` (`409` → client retries) and on a
pending-deletion group (`409`). Operator-blind preserved (new ciphertext + a bumped `name_epoch` int; no key, no
plaintext). Guards green (`openApiGenerateSpecs`, `./gradlew test`, `npm run check`, coverage/changes/doc-link
validators). **Out of scope:** native (apple/android) group-rename parity (web-first, later CRs); the
fingerprint/TOFU authenticity seam (stays deferred, ADR 0014 §5); a rename history / audit log.

## Retrospective

Landed web-first exactly as scoped. A GroupOwner renames a group; the new name re-seals under `K_name` of the
**current** epoch and `name_epoch` tracks it, so a member removed at an earlier epoch can't derive the new key —
forward-secret, consistent with node names. Name-only: no `K_SC` rotation, no re-grant.

- **No crypto changes** — reused cr-0096's `K_name` derivation + the `node-name` seal, so no vector work. The whole
  CR is a thin write path over the read cr-0096 already shipped.
- **Stale-epoch as the FS guard, not a common path.** The client always seals under its **write key** (the highest
  epoch it holds) and sends that epoch; the server enforces `nameEpoch == currentEpoch`. A caller who is current
  succeeds; a stale caller (only the multi-owner concurrent-rotation TOCTOU, or a not-yet-re-granted owner) gets
  `409 stale_epoch` and the client refetches + retries once. This keeps the FS promise without a wrong-key footgun
  (a stale seal is rejected, never stored).
- **UI:** an owner-only inline rename on the group's own name at the top of `GroupMembersPanel` (the group settings
  panel), name flowed in from the hub's already-decrypted nav label + emitted back on save so the switcher + nav
  relabel. Reused the existing inline-edit pattern; no new UI concept.
- **Coverage first-of-its-kind refined→implemented flip:** `uc.spaces.rename-group.yaml` was the repo's first
  `status: planned` coverage file (during the refine); this CR dropped the status and filled real test refs.
- **Deferred, unchanged:** native parity (apple/android), the fingerprint/TOFU seam, a rename history/audit log.
- **Guards:** `openApiGenerateSpecs` + web `generate:api` clean; `./gradlew test` green (5 new rename slice tests);
  `npm run check` green (457 pass, 3 new rename unit tests); coverage/changes/doc-link validators green.
