---
id: cr-0126
title: Fix cross-scope node-delete IDOR — bind the delete to the node's own scope
status: done
satisfies: [uc.memory.browse]
adrs: []
invariants: [operator-blind-e2ee]
---

## Goal

Close a cross-scope IDOR in node deletion. `DELETE /api/scopes/{scopeId}/nodes/{nodeId}` deletes the node
by primary key **without checking the node actually belongs to `scopeId`**. Since every account is a member
of its own personal scope, any authenticated caller can pass their *own* `scopeId` together with a **foreign**
`nodeId` and hard-delete another scope's Memory/Folder/Chapter — irreversibly (nodes have no trash), leaving
the foreign node's media rows and cover blobs orphaned. Foreign node-ids are obtainable in practice: the
`@PermitAll` share-resolve returns `ShareNode.id` for every node in a shared subtree, and a removed group
member keeps the ids it already saw — so this also **defeats membership revocation**. The fix is the guard the
sibling `update` path already applies (resolve the node *within the scope* before touching it), plus a
defense-in-depth scope predicate on the bulk delete itself.

## Context & constraints

- **Authorization is the server's job.** Confidentiality here is cryptographic, but authorization over opaque
  identifiers is enforced server-side (`docs/crosscutting/security-model.md` § "the one guarantee"). A
  membership check that omits the node↔scope binding is a broken-access-control hole: no ciphertext leaks, but
  structure is destroyed across a trust boundary, and other members' content is orphaned.
- **The exact gap.** `NodesHandler.delete` (`backend/.../features/content/NodesHandler.kt:94`) checks only
  `store.isMember(userId, scopeId)`, then calls `deleteSubtree(scopeId, nodeId)`. The sibling `update`
  (`NodesHandler.kt:74`) correctly guards with `store.findInScope(scopeId, nodeId)` first. Inside
  `deleteSubtree` (`NodesStore.kt:122`) the doomed set is seeded with the raw client `nodeId`, and the final
  statement deletes by primary key with **no scope predicate**: `NodeEntity.delete("id in ?1", doomed)`
  (`NodesStore.kt:147`).
- **Use-case.** `uc.memory.browse` — `rename-and-delete-subtree` is the happy path; `authz-boundary` covers a
  *non-member* → 404. Neither exercises the *cross-scope member* case (`nonMemberIs404` can't — the attacker is
  a member of their own scope). This CR adds that missing criterion + test.
- **No contract, no schema, no wire change.** Backend authz logic only; operator-blind untouched (opaque ids
  only). Personal-scope delete keeps working unchanged.
- **Verify:** `cd backend && ./gradlew check` (new `NodesSliceTest` case) + the three trust-chain guards
  (`validate_coverage`, `validate_changes`, `validate_doc_links`).

## Phases

- [x] 1 **backend** — In `NodesHandler.delete`, add `store.findInScope(scopeId, nodeId) ?: return
  DeleteNodeOutcome.NoScope` before `deleteSubtree` (mirroring `update`); and scope the bulk delete in
  `NodesStore.deleteSubtree` to `NodeEntity.delete("scopeId = ?1 and id in ?2", scopeId, doomed)` (defense in
  depth — the seed + traversal already stay in-scope, this closes the primary-key path). · **Done:**
  `./gradlew check` **BUILD SUCCESSFUL in 46s** (compile + all slice tests). Added
  `NodesSliceTest.crossScopeDeleteIsRejected` — a member of scope A doing `DELETE /scopes/{A}/nodes/{node-in-B}`
  gets `404 scope_not_found` and B's node survives; `renameThenDeleteSubtree` (in-scope) still green. The
  pre-fix path returned `204` and hard-deleted the foreign node.
- [x] 2 **coverage** — `docs/coverage/features/memory/use-cases/uc.memory.browse.yaml`: added criterion
  `delete-scoped-to-node` ("deleting a node is bound to the node's own scope — a member of another scope cannot
  delete it; cross-scope → 404, the foreign node survives") with
  `backend: { integration: [NodesSliceTest.crossScopeDeleteIsRejected] }`. · **Done:**
  `python3 scripts/validate_coverage.py` **green** (39 use-cases); all three trust-chain guards green.

## Definition of done

`./gradlew check` green (incl. the new cross-scope test), `uc.memory.browse.yaml` carries the new criterion +
ref, and all three trust-chain guards green.

## Retrospective

- **Planning miss:** the CR named the security fix precisely but glossed a side effect — the `findInScope`
  guard turns "delete an already-gone / non-existent node in your *own* scope" from the old idempotent `204`
  into a `404`. It's the right call (mirrors `update`, keeps the response non-enumerable, no test relied on the
  old behavior), but it's a behavior change for the *legitimate* path that should have been an explicit
  trade-off under "Context & constraints", not left implicit.
- **Execution:** smooth — four edits, `./gradlew check` green on the first run, all three guards green. The H2
  `SessionPurge` shutdown-race in the test log was verified as pre-existing noise (a different slice), not
  ignored.
- **Next time:** when a fix hardens an existing code path, spell out the semantic change for the *non-attack*
  case (here: delete idempotency), not just the attack it closes.
