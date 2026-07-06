---
id: cr-0093
title: Grant-delivery becomes epoch-aware — record grantedEpoch on delivery + re-grant stale members
status: done             # planned → in-progress → done (kept as a record)
satisfies: [en.spaces.scope-key-rotation]
adrs: ["0021", "0024", "0020", "0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Fix a latent correctness gap in **grant-delivery** for scopes that have **rotated**, and deliver the **stale-epoch
trigger** the rotation enabler already declares as a dependency
([en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md) — "grant-delivery
with a *stale-epoch* trigger", §E2). Grant-delivery's `complete` writes the delivered `wrappedGrant` but **never
sets `grantedEpoch`** (only [ScopesStore.rotate](../backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt)
sets it). The worker wraps the **current** epoch key `E_current`, so a member **delivered into an already-rotated
scope** ends up holding `E_current` while recorded at `grantedEpoch = 0` — the client's chain-peel
([deriveEpochKeys](../web/src/features/spaces/epochs.ts)) then treats `E_current` as `E_0` and **cannot read
post-rotation content**. And the reconciler only targets `wrappedGrant is null`, never a **stale** member
(`grantedEpoch < currentEpoch`), so §E2 (a re-wrap that failed at rotation time) strands the member on *awaiting
access* with no auto-recovery.

## Context & constraints

- **Reachable in normal use**, not an exotic edge: add a member to a group that previously had a member removed
  (→ rotated). Untested today — the grant-delivery slice tests
  ([TasksSliceTest](../backend/src/test/kotlin/com/veyq/app/features/tasks/TasksSliceTest.kt)) only seed a **fresh
  Epoch-0** scope, where `grantedEpoch = 0` is coincidentally correct.
- Durable truth already present: [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md)
  lists **"grant-delivery with a stale-epoch trigger"** under *Depends on* and describes the stale member in **§E2**;
  [uc.spaces.add-group-member](../docs/features/spaces/use-cases/uc.spaces.add-group-member.md) §`auto-deliver-group-grant`
  is the concrete flow that mis-records the epoch. [ADR 0024](../docs/decisions/0024-scope-key-rotation.md)
  (epoch chain: the grant is `E_grantedEpoch`, peeled down via chain-links), [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)
  (the pull queue + reconcile-on-claim).
- **Backend-only, no contract + no web change.** The web worker already delivers `E_current`
  ([grantDeliveryWorker.ts](../web/src/features/tasks/grantDeliveryWorker.ts) wraps `scope.key`) and the client
  already peels from `grantedEpoch` correctly — the bug is purely the **server not recording** the epoch it
  delivered. Fix the bookkeeping; the client becomes correct for free.
- Invariants at risk: **none broken** — operator-blind is untouched (the server records only the cleartext epoch
  index it already stores per membership, and stores opaque ciphertext grants). This *restores* forward-secure
  readability for post-rotation members; nothing weakens forward secrecy (the delivered key is `E_current`, never a
  retired epoch handed backwards).
- **Narrow race (documented, self-healing):** if a rotation commits between the worker wrapping `E_current` and the
  `complete` reading `currentEpoch`, the recorded epoch could lead the actual grant by one. It self-heals on the
  next rotation (which re-grants the full directory at the new epoch) and is no worse than today's un-recorded
  state. Not closed here (would need the worker to carry the delivered epoch — a contract change out of scope).

## Phases

- [x] 1 backend — **`TasksStore.complete`** (grant-delivery branch): set `grantedEpoch = scope.currentEpoch`
  alongside `wrappedGrant`, and write when the member is **behind** (`wrappedGrant is null OR grantedEpoch <
  currentEpoch`) so it also overwrites a **stale** grant — keeping the cr-0081 own-scope binding (`and scopeId =
  ?`). **`TasksStore.reconcileGrantDelivery`**: also originate a job for **stale** members (`grantedEpoch <
  currentEpoch`), not just `wrappedGrant is null`. Idempotent + first-online-wins (a member already at
  `currentEpoch` is a no-op). Slice tests: grant-delivery into a **rotated** scope records `grantedEpoch =
  currentEpoch` (the red→green test); a **stale** member is reconciled + re-delivered at the current epoch; a fresh
  Epoch-0 scope is unchanged; a re-delivery no-ops once current. · Done when: `./gradlew test` green.
- [x] 2 web/e2e — extend the live harness: **add a member to an already-rotated group**, run grant-delivery, and
  assert they **read post-rotation content** (proves the bookkeeping fix end-to-end with real crypto). · Done when:
  `VEYQ_E2E=1 npx vitest run` green on a fresh instance.
- [x] 3 coverage — sharpen [uc.spaces.add-group-member.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.add-group-member.yaml)
  `auto-deliver-group-grant` (epoch-aware delivery) + a **`stale-epoch-redeliver`** criterion; note in
  [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md) §E2 that the stale
  member is now **auto-re-granted** (no longer stranded). · Done when: `validate_coverage.py` + `validate_changes.py`
  + `validate_doc_links.py` green.

## Definition of done

Grant-delivery records the **epoch it delivered** (`grantedEpoch = currentEpoch`), so a member added to a rotated
scope reads post-rotation content immediately; and the reconciler **re-grants stale members**
(`grantedEpoch < currentEpoch`), closing §E2 (a failed rotation re-wrap self-heals instead of stranding the member).
Idempotent + first-online-wins + operator-blind (only the cleartext epoch index the server already stores; opaque
ciphertext grants). Backend-only — no contract, no web code change. Coverage + guards green. **Out of scope:** the
narrow claim→complete rotation race (would need the worker to carry the delivered epoch — a contract change);
native parity.

## Notes

- **Why the client needs no change.** [deriveEpochKeys](../web/src/features/spaces/epochs.ts) already sets
  `keys[grantedEpoch] = grantKey` and peels down via chain-links; it was always correct *given* a truthful
  `grantedEpoch`. The server was feeding it a lie (`0`) for post-rotation deliveries.
- **Discovered by cr-0092.** Building the rotation backstop surfaced that only `rotate` maintained `grantedEpoch`;
  grant-delivery (the *other* path that hands out a scope key) never did — so the two drifted the moment a scope
  rotated. cr-0092 itself is unaffected (its rotation re-grants the full directory in one shot, setting
  `grantedEpoch` via `rotate`); this closes the *other* consumer.
- **Verified red→green + live.** The two backend slice tests failed on `main` (proving the gap: `grantedEpoch`
  stayed `0` / a stale member was never reconciled) and pass after the fix. The three-actor rotation e2e was
  extended: a **fourth** member added *after* the rotation is grant-delivered, is recorded at the current epoch,
  and **reads a post-rotation memory** — end-to-end with real crypto against a real backend + MariaDB
  ([scopeRotation.e2e.test.ts](../web/src/features/tasks/scopeRotation.e2e.test.ts), `VEYQ_E2E=1`).
