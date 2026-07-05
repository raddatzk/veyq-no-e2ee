---
id: cr-0088
title: Group member management — roles + remove with scope-key rotation (G3a)
status: done             # planned → in-progress → done (kept as a record)
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
G1/G2 (cr-0032/cr-0086). Web-only; native parity later. Group **delete + orphan rule** (G3b) is a separate slice.

**Split (per the size note below):** this CR delivers **roles (promote/demote) end-to-end** + the full
**remove + rotation backend/contracts/http** (the epoch schema, `removeMember`, `rotateScope`, chain-links). The
**remove UI + the client-side rotation crypto** (epoch-aware scope open + tree seal) proved a wide content-layer
ripple and land in **[cr-0089](cr-0089-scope-key-rotation-web.md)** — so the user-facing "remove" ships **with**
its rotation there, honoring the refinement decision, while the backend is ready here.

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

- [x] 1 contracts — (a) **set-member-role** + **remove-member** ops on `/api/scopes/{scopeId}/members/{userId}`
  (owner-gated; `409 last_owner` guard; `403 not_scope_owner`). (b) The **epoch surface** ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)):
  `currentEpoch` + the caller's `grantedEpoch` on `ScopeSummary`; a **rotation-commit** `POST /api/scopes/{scopeId}/epochs`
  ({ `wrappedPrev`, `grants: [{userId, wrappedGrant}]` }); a chain-link read `GET /api/scopes/{scopeId}/epochs`;
  an `epochIndex` on node create/list (content API). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — roles (`setMemberRole` promote/demote) + **last-owner guard**; `removeMember` (delete the
  membership); the **epoch schema** + migration (`veyq_scope.current_epoch`, a `veyq_scope_epoch` chain-link table,
  `veyq_membership.granted_epoch`, `veyq_node.epoch_index`); the **rotation-commit** handler (owner-gated: bump the
  epoch, store the chain-link, apply the re-grants atomically); **grant-delivery** reconciler trigger widened to
  *grant-less or stale-epoch*. Slice tests (promote/demote; last-owner-guard rejects; remove deletes; rotation-commit
  bumps + stores + re-grants; a stale-epoch membership is re-granted; non-owner rejected; the removed member's grant
  is gone). · Done when: `./gradlew test` green.
- [x] 3 http — role-change / remove / rotation-commit unauth boundary in `http/spaces/scopes.http` (the owner-gated
  + rotation happy-path stays integration-covered). · Done when: the unauth assertions hold headless.
- [x] 4 web — member-row **role actions** (promote to co-owner / demote, owner-only, last-owner-guard error) in
  `GroupMembersPanel` + the `setMemberRole` data layer. · Done when: `npm run check` green + live: an owner
  promotes/demotes a co-owner. **(The remove UI + the client-side rotation — mint `E_{n+1}`, re-grant remaining,
  epoch-aware scope open — moved to [cr-0089](cr-0089-scope-key-rotation-web.md) so "remove ships with rotation"
  lands together there; the remove/rotation **backend** is delivered here.)**
- [x] 5 coverage — fill `uc.spaces.manage-group-members.yaml` (drop `status: planned`): promote-demote /
  last-owner-guard / remove-member / remove-rotates-forward / operator-blind-rotation → the backend `…SliceTest`
  refs + the web `setMemberRole` test + the `http` boundary (the web remove-UI + client rotation land in cr-0089).
  · Done when: `python3 scripts/validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A GroupOwner **promotes/demotes** co-owners under the **last-owner guard**, verified live (promote → co-owner,
demote, last-owner rejected). The **remove + rotation backend** is delivered + slice-tested: `removeMember`
(membership delete), the **epoch schema** (V25), and `rotateScope` (bump epoch, store the chain-link, re-grant
remaining) — operator-blind (only opaque epoch links / grants / epoch indices reach the server). Coverage + the
three trust-chain guards green. **In cr-0089** (so "remove ships with rotation"): the remove UI + the client-side
rotation crypto (mint `E_{n+1}`, re-grant remaining, epoch-aware scope open + tree seal) + the two-account
forward-secrecy live smoke. **Out of scope (later):** group delete + orphan rule (G3b); consent opt-in (G2b);
fingerprint hardening; native parity; the **async stale-epoch grant-delivery backstop** (v1 re-grants all
remaining members synchronously in the commit, so no member is left stale).

## Notes

- **Size flag.** The rotation (phases 1–2 epoch surface, phase 4 client crypto + the epoch-aware scope-open + tree
  seal) is the bulk of this CR and cuts across the content layer. If it proves too broad in one pass, split the
  **rotation substrate** (en.spaces.scope-key-rotation) into its own CR and keep this one to roles + membership
  delete — but per the refinement decision, remove **ships with** rotation, so they land together here by default.

## Retrospective

- **Planning — the split was pre-authorized, and needed.** I sized G3a as one CR with a "split if too broad"
  clause. Once the contracts were in and I traced the web ripple, the client rotation (epoch-aware scope open +
  tree seal across `tree.ts` + every caller) was clearly a wide, security-critical content-layer change that
  shouldn't be rushed onto the end of an already-large backend CR. Splitting it to cr-0089 (keeping "remove ships
  with rotation" by moving the remove **UI** there too) was the right call — but I could have seen the content-layer
  ripple at *refinement* time (the ADR already says names/slug/K_mem key off the epoch), and sliced G3a as
  roles-vs-rotation from the start.
- **Execution — one real bug, caught by a test: stale same-request read-back.** `setMemberRole` persisted in its
  own `@Transactional`, then the handler re-read the row to return it — and got the pre-update role from a stale
  persistence context (green DB, stale read). The promote slice test caught it (`Actual: MEMBER`). Fixed by
  building the response from the role just set. Lesson: a `@Transactional` store write + a follow-up read in the
  same non-transactional handler call can see stale state — return what you wrote, or wrap both.
- **Process — well.** Refined state (ADR 0024 + enabler + use-case) landed before the CR; the audit against the
  pinned crypto vectors happened *before* committing to the dedup split (which turned out to be a non-issue,
  deferred); phased backend → green at each gate. Committed a reviewable phases-1–3 checkpoint before the web.
- **Next time:** when a use-case says a key "is the navigation root" (names/slug/dedup key off it), treat any
  rotation of that key as a content-layer refactor from the outset and slice it out before writing the CR.
