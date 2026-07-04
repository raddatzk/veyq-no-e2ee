---
id: cr-0081
title: Harden the task queue — scope-bind the grant write + reject client-forged structural jobs
status: done
satisfies: []            # security hardening of the cr-0076 mechanism; least-privilege, no use-case change
adrs: ["0021", "0020"]
invariants: [operator-blind-e2ee]
---

## Goal

Close a least-privilege gap found in the [security review](cr-0076-background-task-mechanism.md) of the
background-task mechanism ([cr-0076](cr-0076-background-task-mechanism.md)). `TasksStore.complete` authorized the
caller against the **job's** scope but then wrote the client-supplied `wrappedGrant` onto the membership named by
`job.resourceRef` **without checking that membership belongs to the job's scope**; and `enqueue` accepted a
fully client-controlled `capabilityTag`, including the structural `GRANT_DELIVERY` tag. Combined, a grant-holder
in *any* scope could craft a delivery job targeting a foreign grant-less membership id.

**Not a new exploitable break** — grant-less membership ids are never disclosed cross-scope (the member directory
returns `userId`, not the membership id; `claim` only resolves a target for a scope you already hold a grant in),
and the reachable in-scope case is already the documented, accepted "Plain-auth / malicious key-holder" gap
([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)). But the safety rested on an identifier-
secrecy *accident* rather than an explicit invariant, so a future change that ever exposed a membership id would
turn it into a real cross-scope break. This makes the boundary explicit.

## Context & constraints

- **Bind the write to the job's scope.** The grant write is authorized against `job.scopeId`, so it may only
  ever land on a membership *in that scope* — enforced in the query, not assumed.
- **Structural tags are server-originated only.** `grant-delivery` jobs are born solely via
  `reconcileGrantDelivery`; the client `enqueue` front door rejects the tag (the store enforces it too, defence
  in depth). This was already the documented intent — now it is enforced.
- Operator-blind untouched: the server still only relays opaque ciphertext.
- **Verify:** `./gradlew -p backend check`.

## Phases

- [x] 1 backend — (a) `complete`'s grant write gains `and scopeId = ?3` (bound to `job.scopeId`); (b) `enqueue`
  returns null for the `GRANT_DELIVERY` tag, and `TasksResource.enqueueTask` rejects it with a clear `400`
  before the handler. Tests: `enqueue` with the structural tag → 400; a grant-delivery job in scope A whose
  `resourceRef` points at a membership in scope B is completed by an A-key-holder but the B membership stays
  grant-less. · Done when: `./gradlew -p backend check` green (the legitimate same-scope delivery still works).

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Scope-bound grant write + structural-tag rejection | Status |
|---|---|---|
| backend | `complete` scope-binds the write; `enqueue` rejects structural tags | ☑ this CR |
| web / native | no change (they never enqueue structural jobs) | — n/a |

## Definition of done

`complete` writes a grant only onto a membership in the job's own scope, and `enqueue` refuses the structural
`grant-delivery` tag — so the delivery write's authorization boundary is explicit, not resting on identifier
secrecy. `./gradlew -p backend check` green (regression tests for both, the legitimate delivery unaffected).

## Retrospective

Two-line hardening + two regression tests, straight out of the security review. The review's initial "HIGH
cross-tenant" framing was a false positive (membership ids aren't reachable cross-scope, and the in-scope case
is the accepted Plain-auth gap) — the adversarial verification pass caught that. What remained was a genuine
least-privilege defect worth fixing precisely because the previous safety was accidental. Fingerprint/TOFU
pinning (the real fix for the malicious-key-holder delivery gap) stays the documented later seam
([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md) move 4).
