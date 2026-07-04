---
id: cr-0082
title: Member-invite management — list, re-issue link, and revoke pending member invites (owner-gated)
status: done
satisfies: [uc.spaces.manage-member-invites]
adrs: ["0020", "0007"]
invariants: [operator-blind-e2ee]
---

## Goal

Round out member-add ([cr-0077](cr-0077-member-add-onboarding.md) / [cr-0080](cr-0080-invite-link-handoff.md))
with the **admission-management** side the owner currently lacks. Today the owner mints a member invite and then
has a blind spot: the member directory shows only **joined** members, so a person who was **invited but hasn't
joined** is invisible — the owner can't track them, **re-issue** their link if it was lost, or **revoke** a
mistaken invite. This realizes [uc.spaces.manage-member-invites](../docs/features/spaces/use-cases/uc.spaces.manage-member-invites.md):
the owner **lists** pending member invites, **re-issues** one (fresh secret + copyable link), and **revokes**
one.

It mirrors the Server-Owner invite management that already exists for tenant-owner invites
([uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md): resend / reassign /
revoke, [cr-0009](cr-0009-create-tenant-structural.md)) — applied to **MEMBER-role** invites and gated on the
tenant **OWNER** membership. Removing a **joined** member + key rotation stay out of scope (a later slice —
[ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md) is additive-only).

## Context & constraints

- **Reuse the invite lifecycle + gates.** The soft ACTIVE → SUPERSEDED / EXPIRED transitions (matched by hash,
  replay-rejected) already exist ([cr-0009](cr-0009-create-tenant-structural.md)); `mintMemberInvite`
  (supersede-by-email + mint) and the `isOwner(callerId, tenantId)` gate exist from cr-0077; the copyable-link
  secret return exists from [cr-0080](cr-0080-invite-link-handoff.md). This CR wires them into list / re-issue /
  revoke — little new machinery.
- **Owner-gated admission.** Every endpoint is gated on the caller holding the tenant OWNER membership (a code
  check, like `inviteTenantMember`) — a non-owner is `403 not_tenant_owner`.
- **Operator-blind.** Listing returns only invite **metadata** (email, status, expiry) the server already
  stores — never a plaintext secret. Re-issue returns the fresh secret only to the owner-gated caller over TLS
  (as [cr-0080](cr-0080-invite-link-handoff.md) already does at mint).
- **Additive-safe.** Revoking a *pending invite* touches no grant and no joined member (revocation of a
  *member* is a separate later slice).
- **Web-first** ([ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)); native is each
  surface's own later CR.
- **Verify:** `./gradlew -p backend check` + the `http/` boundary + `cd web && npm run check`; a live browser
  smoke.

## Phases

- [ ] 1 contracts — extend `tenants.openapi.yaml`: **`GET /api/tenants/{tenantId}/member-invites`** (list
  pending MEMBER invites → `[{ inviteId, email, inviteStatus, inviteExpiresAt }]`), **`POST
  …/member-invites/{inviteId}/reissue`** (→ `MemberInviteState` incl. `bootstrapSecret`, cr-0080), **`DELETE
  …/member-invites/{inviteId}`** (revoke → 204). · Done when: contracts lint green; clients build backend + web.
- [ ] 2 backend — owner-gated (isOwner) list (query ACTIVE, unexpired, role=MEMBER invites for the tenant),
  re-issue (supersede the current invite for that email + mint a fresh secret, reusing `mintMemberInvite`), and
  revoke (soft-transition the invite to a terminal state so its secret replays as `invite_no_longer_valid`).
  · Done when: `./gradlew -p backend check` green; tests cover list (owner-only; excludes consumed/expired),
  re-issue (old secret dies + new one accepts), revoke (secret then rejected at accept), and the non-owner 403.
- [ ] 3 http — a boundary/authz `http/…` file: the three endpoints require auth (401) and reject a non-owner
  (403); revoke of an unknown invite → 404. · Done when: the `.http` assertions pass against a fresh instance.
- [ ] 4 web — a **pending-invites** section in [`TenantMembersPanel`](../web/src/features/spaces/TenantMembersPanel.vue)
  (owner-only), beside the joined-member directory, each row (email + status/expiry) with **re-issue** (→ the
  cr-0080 copy-link view) + **revoke**. i18n EN+DE. · Done when: `npm run check` green; component tests cover
  the owner-only pending list + the re-issue/revoke actions.
- [ ] 5 coverage — fill the three criteria of
  [uc.spaces.manage-member-invites.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.manage-member-invites.yaml)
  (`list-pending-member-invites`, `reissue-invite-link`, `revoke-member-invite`), drop `status: planned`; note
  apple/android as gaps. · Done when: `validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py`
  green.
- [ ] 6 verify (browser smoke) — live: the owner invites two people, sees both **pending**, re-issues one
  (copies the fresh link), revokes the other (its link then fails at accept), and once someone joins they move
  from **pending** to the member directory. · Done when: confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | List / re-issue / revoke pending member invites | Status |
|---|---|---|
| backend / http | owner-gated list + re-issue (fresh secret) + soft-revoke | ◻ this CR |
| web | pending-invites section in the members panel (re-issue → copy link, revoke) | ◻ this CR |
| apple / android | native member-invite management | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A tenant owner can list the tenant's pending member invites, re-issue one (fresh single-use secret + copyable
link, the old one dead), and revoke one (its secret then rejected at accept) — all owner-gated, additive-safe,
and operator-blind (invite metadata only; the secret re-returned only to the owner). `./gradlew -p backend
check` + the `http/` boundary + `npm run check` green; `uc.spaces.manage-member-invites` fully covered
(`status: planned` dropped); the three doc validators green; the live smoke confirmed. Removing a **joined**
member + key rotation remain a later slice.

## Retrospective

_(to be written at close)_
