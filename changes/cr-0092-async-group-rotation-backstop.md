---
id: cr-0092
title: Async group-key-rotation backstop — a SCOPE_ROTATION job on the blind task queue
status: done             # planned → in-progress → done (kept as a record)
satisfies: [en.spaces.scope-key-rotation]
adrs: ["0024", "0021", "0025", "0020", "0014"]
invariants: [operator-blind-e2ee]
---

## Goal

Complete the **forward-secrecy** half of a tenant member-removal (cr-0091, Fork 1b). When a TenantOwner removes a
member, their co-shared GROUP memberships are cut at **layer-1** (membership deleted → 403) but the group is **not
rotated** — the removing owner isn't necessarily a key-holder there, so they *cannot* mint the new epoch. This CR
**queues the rotation** as a **`SCOPE_ROTATION` job on the existing operator-blind task queue** ([ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md))
and has the **next online remaining key-holder** complete it on-device (mint E_{n+1} + re-grant the remaining
members, reusing the cr-0089 rotation crypto). This closes [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md)
§E1 ("no key-holder online → queued") and the deferred alternate flows
[uc.spaces.remove-tenant-member](../docs/features/spaces/use-cases/uc.spaces.remove-tenant-member.md) A4 +
[uc.spaces.manage-group-members](../docs/features/spaces/use-cases/uc.spaces.manage-group-members.md) A2.

## Context & constraints

- Durable truth: [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md)
  §E1 (queued rotation, first-online-wins, idempotent), [ADR 0024](../docs/decisions/0024-scope-key-rotation.md)
  (epoch-chain rotation: mint E_{n+1}, wrap E_n, re-grant remaining), [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)
  (pull job queue: claim/complete/fail, reconcile-on-claim + scheduled originator), [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md)
  (**groups rotate**, client-driven; tenant = ACL-revoke, no rotation).
- Reuses **everything material** — this CR only wires existing pieces together:
  - The **task queue** (cr-0076/0079): `TasksResource` (`claim`/`complete`/`fail`), `TasksStore.claim` +
    `reconcile*`, `JobEntity`, `CapabilityTags`, the scheduled originator; the web `runGrantDeliveryWorker` +
    45s polling ([grantDeliveryWorker.ts](../web/src/features/tasks/grantDeliveryWorker.ts), [grantDeliveryPolling.ts](../web/src/features/tasks/grantDeliveryPolling.ts)).
  - The **rotation commit** (cr-0088/0089): `POST /api/scopes/{scopeId}/epochs` (`ScopesHandler.rotateScope` →
    `ScopesStore.rotate`), and the **client-side** `rotateGroupForRemoval(scopeId, remaining)`
    ([scopes.ts](../web/src/features/spaces/scopes.ts)) — mint + wrap + re-grant, already conformance-clean.
  - The **removal cascade** (cr-0091): `TenantHandler.removeMember` already walks the target's memberships and
    deletes the shared/orphan-transferred GROUP memberships — the exact spot to originate a rotation job.
- **Why a new signal is needed (the crux).** A removal cut deletes the membership and changes **no epoch** —
  `currentEpoch` is unchanged and remaining members stay at `grantedEpoch == currentEpoch`. So there is **no
  stale-epoch mismatch to detect**; the cut leaves no trace. The rotation-pending state must be recorded
  **explicitly** — a `SCOPE_ROTATION` job (`resourceRef == scopeId`), not the grant-delivery stale-epoch trigger
  (which only re-grants *after* an epoch already advanced). One live job per scope **coalesces** multiple removals
  (a single rotation re-grants exactly the current member set).
- Invariants at risk:
  - **Operator-blind — legitimately.** The server only knows "scope X needs a client-driven rotation" (opaque job
    row: tag + scopeId) and stores the resulting **ciphertext** epoch-link + re-grants. It never mints, wraps, or
    holds a key. The mint + wrap happen **on a key-holder's device**.
  - **Forward secrecy for new content** ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)): after the
    backstop runs, new content sealed under E_{n+1} is unreadable to the ex-member's retained E_n. Already-read is
    not retractable (by design); until a key-holder is online, layer-1 already blocks *new* fetches.
- New surface: a **`SCOPE_ROTATION`** capability tag + its origination in the removal cascade (idempotent, one live
  job per scope), a **claimed-rotation** job the client can act on, and a **client rotation worker** that drives
  `rotateGroupForRemoval` and completes the job. No new crypto, no new endpoints beyond the task-queue surface.

## Phases

- [x] 1 contracts — register the **`SCOPE_ROTATION`** capability tag (opaque token, mirroring `GRANT_DELIVERY`) and
  the **claimed-rotation** job shape (the claim response carries `scopeId`; the client fetches the member directory
  + epoch links it already has endpoints for — no key in the job). No new endpoint (claim/complete/fail are
  generic). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — **originate** a `SCOPE_ROTATION` job in `TenantHandler.removeMember` for each **shared/orphan-
  transferred** GROUP whose membership was cut (idempotent: skip if a live rotation job for that scope exists);
  the **reconcile-on-claim + scheduled originator** back it up. `complete` marks the job DONE once the caller's
  rotation-commit advanced the epoch (idempotent, first-online-wins; a second claim after the epoch already moved
  is a no-op DONE). Own-scope soft-deletes (personal + solo groups) originate **no** rotation. Slice tests:
  removal originates a rotation job per co-shared group (none for own/soft-deleted scopes); a key-holder claim
  returns it; completing after a rotate-commit clears it; a second removal coalesces into the one live job; a
  non-key-holder cannot claim it. · Done when: `./gradlew test` green.
- [x] 3 web — a **rotation worker** (`runScopeRotationWorker`, mirroring `runGrantDeliveryWorker`): claim
  `SCOPE_ROTATION` for a held scope → `rotateGroupForRemoval(scopeId, remaining)` → `complete`. Wire it into the
  existing key-holder polling so a remaining owner/member online completes queued rotations without opening the
  group. · Done when: `npm run check` green + live: owner-A removes a member co-sharing group G that owner-A
  doesn't hold → key-holder-B online rotates G forward (new epoch), the ex-member is locked out of new content.
- [x] 4 http — `SCOPE_ROTATION` claim unauth + non-key-holder boundary on the tasks surface (the rotation happy
  path is a signed key-holder flow → slice/live covered). · Done when: the unauth assertions hold headless.
- [x] 5 coverage — add a **`coshared-group-rotation`** acceptance criterion to `uc.spaces.remove-tenant-member.yaml`
  (A4 closed) and cross-reference `uc.spaces.manage-group-members.yaml` A2 (the same backstop) → the backend
  `…SliceTest` refs + the web worker test + the `http` boundary. · Done when: `validate_coverage.py` +
  `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

Removing a tenant member queues a **`SCOPE_ROTATION`** job per co-shared / orphan-transferred group; the **next
online remaining key-holder** completes it on-device — mint E_{n+1}, wrap E_n, re-grant the remaining members —
restoring **forward secrecy for new content** without the removing owner ever holding those keys. Idempotent +
first-online-wins + coalescing (one live job per scope). Operator-blind (opaque job row + ciphertext epoch-link;
no key server-side). Coverage + guards green. **Out of scope:** re-keying the **tenant** scope (ADR 0025 — ACL-
revoke, no rotation); the grant-delivery **stale-epoch** re-grant refinement (a separate polish; here the
rotation-commit re-grants the current member set in one shot); native (apple/android) rotation-worker parity.

## Notes

- **Why not the grant-delivery stale-epoch trigger?** That path re-grants members whose `grantedEpoch <
  currentEpoch` — it presupposes a rotation **already happened**. Fork 1b's problem is the opposite: the rotation
  **hasn't** happened and nothing marks that it should. Hence an explicit rotation job. (The stale-epoch re-grant
  remains a valid *later* refinement for members who were grant-less at rotation time — noted, not built here.)
- **One job per scope, coalescing.** Multiple removals from the same group before a key-holder is online collapse
  into one pending rotation — a single mint + re-grant of the surviving member set is forward-secure regardless of
  how many were cut. Reconcile must therefore dedup on `scopeId` (a live PENDING/LEASED rotation job for the scope
  suppresses a second).
- **Serves two use-cases, one mechanism.** The identical backstop covers uc.spaces.remove-tenant-member A4 (the new
  trigger) and uc.spaces.manage-group-members A2 ("no key-holder online at group-removal time") — cr-0088/0089
  rotate synchronously when the remover *is* a key-holder; this is the async fallback when they are not.
- **Live-verified (retro).** Beyond the slice/unit tests: the `SCOPE_ROTATION` unauth + non-key-holder boundary was
  driven headless via `ijhttp http/tasks/tasks.http` (requests 12-13) against a real MariaDB-backed backend, and
  the full three-actor forward-secrecy flow — owner-A removes co-sharer C from a group A doesn't hold → the queued
  rotation is unclaimable by A and by removed-C → remaining key-holder B rotates G forward (epoch 0→1), re-grants
  the survivor, leaves a real E_1←E_0 chain-link, C gone, job cleared — was verified end-to-end with real crypto by
  [scopeRotation.e2e.test.ts](../web/src/features/tasks/scopeRotation.e2e.test.ts) (`VEYQ_E2E=1`, on a fresh instance).
