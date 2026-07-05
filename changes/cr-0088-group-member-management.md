---
id: cr-0088
title: Group member management — roles + remove with scope-key rotation (G3a)
status: in-progress
satisfies: [uc.spaces.manage-group-members]
adrs: ["0024", "0014", "0007", "0020", "0021", "0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **GroupOwner run the roster** — promote a member to co-owner, demote a co-owner, and **remove** a member —
under the **last-owner guard**; and make "remove" mean something cryptographically: removal **rotates the scope
content key forward into a new epoch** granted only to the remaining members, so the removed member reads
pre-removal content but **never** post-removal content, even against a malicious operator, **without
re-encrypting anything** ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)). The G3a slice on top of
G1/G2 (cr-0032/cr-0086). Roles + membership delete are small; **the rotation is the dominant piece** — it touches
the scope, membership, **content**, and tasks surfaces. Web-only; native parity later. Group **delete + orphan
rule** (G3b) is a separate slice.

## Context & constraints

- Durable truth: [uc.spaces.manage-group-members](../docs/features/spaces/use-cases/uc.spaces.manage-group-members.md),
  [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md), and
  [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (the epoch model — read it first). Authority + the
  last-owner guard: [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §4 + [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md).
- Reuses: the member directory + `addGroupMember` (cr-0086), grant-delivery ([cr-0076](cr-0076-background-task-mechanism.md)/[cr-0079](cr-0079-web-grant-delivery-polling.md))
  — its reconcile predicate **widens** from *grant-less* to *grant-less or stale-epoch* ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)/[ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)),
  the content-key hierarchy + tree crypto (cr-0026/0060). **No new crypto primitive** — the epoch chain composes
  `symWrap`/`asymWrap`.
- New surface:
  - **Roles/remove:** set a member's role (OWNER/MEMBER) + delete a membership, owner-gated, last-owner-guarded.
  - **Epoch state** ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)): the scope carries a **current
    epoch** counter + the opaque **epoch-chain links** (`symWrap(E_{n+1}, E_n)`); each membership carries its
    **granted epoch**; each content node carries its **epoch index**. A **rotation-commit** op (owner posts a new
    epoch: the chain-link + re-grants to remaining members) + a read of the chain-links so a member peels down to
    read old epochs.
- Invariants at risk:
  - **Operator-blind** — the server stores only opaque epoch-chain links, per-member grants, a granted-epoch
    marker, and a per-node epoch index; **no key, no name**. The epoch index is operational metadata (inferable
    from timing). No path surfaces a key.
  - **Forward-confidentiality is the point** — new content must seal under the **current** epoch, and the removed
    member's membership must be deleted (layer-1 403) *and* not re-granted the new epoch. A bug that seals new
    content under an old epoch, or leaves the ex-member a grant, breaks the guarantee.
  - **Last-owner guard** ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)) — the last OWNER
    cannot be demoted or removed; enforced by code ([ADR 0008](../docs/decisions/0008-centralized-http-auth.md)).

## Phases

- [ ] 1 contracts — (a) **set-member-role** + **remove-member** ops on `/api/scopes/{scopeId}/members/{userId}`
  (owner-gated; `409 last_owner` guard; `403 not_scope_owner`). (b) The **epoch surface** ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)):
  `currentEpoch` + the caller's `grantedEpoch` on `ScopeSummary`; a **rotation-commit** `POST /api/scopes/{scopeId}/epochs`
  ({ `wrappedPrev`, `grants: [{userId, wrappedGrant}]` }); a chain-link read `GET /api/scopes/{scopeId}/epochs`;
  an `epochIndex` on node create/list (content API). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [ ] 2 backend — roles (`setMemberRole` promote/demote) + **last-owner guard**; `removeMember` (delete the
  membership); the **epoch schema** + migration (`veyq_scope.current_epoch`, a `veyq_scope_epoch` chain-link table,
  `veyq_membership.granted_epoch`, `veyq_node.epoch_index`); the **rotation-commit** handler (owner-gated: bump the
  epoch, store the chain-link, apply the re-grants atomically); **grant-delivery** reconciler trigger widened to
  *grant-less or stale-epoch*. Slice tests (promote/demote; last-owner-guard rejects; remove deletes; rotation-commit
  bumps + stores + re-grants; a stale-epoch membership is re-granted; non-owner rejected; the removed member's grant
  is gone). · Done when: `./gradlew test` green.
- [ ] 3 http — role-change / remove / rotation-commit unauth boundary in `http/spaces/scopes.http` (the owner-gated
  + rotation happy-path stays integration-covered). · Done when: the unauth assertions hold headless.
- [ ] 4 web — member-row **actions** (promote/demote/remove, owner-only, last-owner-guard UX) in `GroupMembersPanel`;
  the **client rotation on remove** (mint `E_{n+1}`, `symWrap(E_{n+1}, E_n)`, re-grant `E_{n+1}` to remaining members
  via the directory pubkeys, POST the rotation-commit); the **scope-open path** handles epochs (open grant →
  `E_current`, fetch chain-links, peel to `{E_0..E_current}`, decrypt each node by its `epochIndex`); **new content
  seals under `E_current` + tags its epoch**; the grant-delivery worker re-grants stale-epoch members. · Done when:
  `npm run check` green + live (**two accounts**): owner removes a member → the ex-member keeps old Memories but a
  **new** Memory is unreadable to them (verify at the DB the new node's epoch + that the ex-member holds no current
  grant); a remaining member reads old + new seamlessly.
- [ ] 5 coverage — fill `uc.spaces.manage-group-members.yaml` (drop `status: planned`): map promote-demote /
  last-owner-guard / remove-member / remove-rotates-forward / operator-blind-rotation to the backend `…SliceTest`
  refs + the web data-layer/crypto tests + the `http` boundary; keep apple/android as gaps. · Done when:
  `python3 scripts/validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A GroupOwner promotes/demotes co-owners and removes members under the last-owner guard; removal deletes the
membership (instant 403) **and** rotates the scope key forward so the removed member cannot read any content added
after their removal — verified live with a second account + at the DB (new node under the new epoch; the ex-member
holds no current-epoch grant), while remaining members read old + new content seamlessly. Operator-blind verified:
only opaque epoch links / grants / epoch indices reach the server, never a key or a name. Coverage + the three
trust-chain guards green. **Out of scope (later):** group delete (soft + grace) + the orphan rule (G3b); the
consent opt-in (G2b); fingerprint verification hardening; native parity. **v1 rotation scope:** the
rotation-commit **re-grants every remaining member synchronously** (the client holds all pubkeys from the
directory), so no member is left stale — the **async stale-epoch grant-delivery backstop** (ADR 0024 §5, for a
member whose identity is momentarily unwrappable / a very large group) is **deferred**; the reconciler stays
grant-less-only for now.

## Notes

- **Size flag.** The rotation (phases 1–2 epoch surface, phase 4 client crypto + the epoch-aware scope-open + tree
  seal) is the bulk of this CR and cuts across the content layer. If it proves too broad in one pass, split the
  **rotation substrate** (en.spaces.scope-key-rotation) into its own CR and keep this one to roles + membership
  delete — but per the refinement decision, remove **ships with** rotation, so they land together here by default.
