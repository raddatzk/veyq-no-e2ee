---
id: cr-0095
title: Consent gate holds on the rotation path — a rotation never grants a pendingConsent proposal
status: done
satisfies: [uc.spaces.consent-group-add]
adrs: ["0014", "0024", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Close a consent-gate bypass found in a security review of the groups epic. cr-0094 gates `K_SC` **before** the wrap
for a soft `pendingConsent` proposal — grant-delivery skips soft memberships ([`reconcileGrantDelivery`], and
[`complete`] carries an explicit `and pendingConsent = false`). The **scope-key rotation** path ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md))
did **not** carry that guard: [`ScopesStore.rotate`] wrote every supplied grant onto its membership unconditionally.
Because the owner-facing member directory hands **owners** the pending rows, the honest client's
`rotateGroupForRemoval` re-grants the *whole* directory — so the ordinary "remove a member → rotate" flow, whenever an
open proposal exists, silently wrapped the fresh epoch's `K_SC` onto a member who never accepted. That surfaced the
group in their app (a working grant) with no consent, and a subsequent Decline then deleted a *granted* membership
**without** rotation (a forward-secrecy hole). This CR makes the consent gate hold on the rotation path too.

## Context & constraints

- Durable truth: [uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md)
  (acceptance `soft-membership-gates-key` — "K_SC is never wrapped to a pending invitee"; extended here to name the
  rotation path explicitly), [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §5 (consent-first,
  never wrap-then-soft), [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (the rotation re-grant), and the
  client-tier honesty of [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) (the server-side skip is the
  honest-operator enforcement point — it must be consistent across *every* grant-writing path, not just delivery).
- Invariant at risk: **operator-blind / consent-first.** No new server-visible surface — this only *removes* an
  unintended `K_SC` delivery. The server still stores only ciphertext + the boolean + the flag.
- Bug class: a security invariant enforced on one write path (`complete`) but not a second, later-added one
  (`rotate`). The fix mirrors the existing predicate rather than inventing a new mechanism.

## Change

- **backend** ([`ScopesStore.rotate`](../backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt)):
  the re-grant query gains `and pendingConsent = false`, so a soft proposal is skipped in a rotation exactly as it is
  in grant-delivery's `complete`. This is the essential (honest-operator) fix.
- **web** ([`rotateGroupForRemoval`](../web/src/features/spaces/scopes.ts)): filter `!m.pendingConsent` out of the
  re-grant set before wrapping — defense-in-depth + it avoids a useless wrap (the directory hands owners the pending
  rows, which is why the honest client included them).
- **Finding 2 (documentation, folded in):** the "resolve is owner-gated → no enumeration oracle" claim was
  overstated — any tenant member can `createGroup` and become an owner, so username → identity-pubkey → consent-flag
  are effectively tenant-member-enumerable (within the metadata model, not a leak). Corrected honestly in
  [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §5, [security-model.md](../docs/crosscutting/security-model.md)
  (server-visible list), the `resolveGroupMember` doc-comment, and the `scopes.openapi.yaml` resolve description.

## Phases

- [x] 1 backend — `rotate` gains the `pendingConsent = false` guard; web `rotateGroupForRemoval` filters pending
  rows; new slice test `ConsentGroupAddSliceTest.rotationNeverGrantsAPendingProposal` (a rotation that lists a soft
  proposal in its grants leaves it grant-less + pending + still delivery-skipped). Finding-2 doc corrections.
  · Done when: `./gradlew test` green (10/10 in the slice) + `npm run check` green.
- [x] 2 coverage — add the new test ref to `soft-membership-gates-key`; the criterion text names the rotation path.
  · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A scope-key rotation never (re-)grants a `pendingConsent` proposal — the consent gate holds on the rotation path as
it does on grant-delivery. Slice test proves it; coverage records it; guards green. Operator-blind preserved (the
change only removes an unintended delivery). **Out of scope:** the Finding-3 redesign of the invite-name seal
(shared static `K_name` vs. the one-off owner seal) — refined separately (see [ADR 0027 draft] + inbox #14).

## Retrospective

- **Planning miss (cr-0094):** the consent gate was enforced on the two delivery paths (`reconcile` + `complete`)
  but the rotation re-grant — a *third* path that writes `wrappedGrant` — was never enumerated as a gate site. Root
  cause: no single "every place that writes a grant must honor pendingConsent" checklist; the invariant lived as
  two ad-hoc predicates, not one enumerated rule. The slice tests for cr-0094 never crossed consent with rotation.
- **Next time:** when a CR adds a server-enforced crypto gate, list *all* write paths to the guarded column up front
  and assert the predicate on each (here: `complete`, `reconcile`, **and** `rotate`) — and add at least one test
  that crosses the new feature with the pre-existing lifecycle (remove/rotate), not just the feature in isolation.
