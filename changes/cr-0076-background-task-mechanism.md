---
id: cr-0076
title: Operator-blind background-task mechanism (pull queue + grant-delivery as the first job)
status: done
satisfies: []            # cross-cutting infra — realizes docs/crosscutting/background-tasks.md (ADR 0021); no standalone use-case
adrs: ["0021", "0020"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the **operator-blind background-task mechanism** ([ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md),
[docs/crosscutting/background-tasks.md](../docs/crosscutting/background-tasks.md)): a **pull-based** job
mechanism **in the veyq backend** (no broker), an **equal, self-selecting keyed-worker pool** (any interactive
client *or* the later agent), an opaque **scope-bound job envelope** (cleartext `scopeId` + opaque
`capabilityTag` + `resourceRef` + lease bookkeeping; **body sealed** under a `K_SC`-derived key), and an
**at-least-once + idempotent** claim/lease. It is validated end-to-end by its **first, trivially-blind consumer**
— the `grant-delivery` job that wraps a scope key to another member ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)).

**This CR builds the mechanism + the delivery half of member-add** (the worker that wraps `K_SC` for a pending
member). The **human-facing onboarding** that *produces* pending members (the MEMBER invite + registration + the
owner UI) is [cr-0077](cr-0077-member-add-onboarding.md); here that side is exercised by a **seeded grant-less
membership**. Transcode/ML job types, the SSE push channel, a presence table, and native worker loops are
**explicit non-goals** (later, own CRs).

## Context & constraints

- **Blind coordinator.** The server stores opaque envelopes + relays sealed results; it never holds a scope key,
  never unseals a body, never picks a content-semantic type. Routing is by `scopeId` (already known via
  membership) + an **opaque `capabilityTag`** from a fixed public registry (in `contracts/`, like the crypto
  vectors — the first tag is `grant-delivery`). The residual metadata leak is **correlation-bounded, not
  label-bounded** ([ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md); self-hosted moot) —
  documented, not hidden.
- **Pull + self-selecting equal pool.** A worker's claim carries its capability tags; the server hands back a
  claimable job where `scope ∈ my memberships ∧ tag ∈ my tags`. No presence/advertisement table (that arrives
  only with the later SSE push).
- **Delivery = any key-holder, first-online-wins, automatic.** Every member holds `K_SC`, so any member's client
  may complete a `grant-delivery` job; the wrap runs on-device (`asymWrap` to a **foreign** identity — the first
  such wrap in the codebase), the completion is idempotent (no-op if a grant already exists).
- **Persistence: buy, don't build.** Resolve JobRunr vs. db-scheduler vs. plain MariaDB `SELECT … FOR UPDATE
  SKIP LOCKED` (≥10.6) in Phase 2; only the **blind layer** (envelope, key-eligibility, sealed body/result, tag
  registry) is veyq-specific, kept a thin decoupled module (extraction-ready, not extracted).
- **Web-first** ([ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)); the native
  worker loop is deferred to its own CRs.
- **Verify:** `./gradlew -p backend check` + the `http/` signed happy-path + `cd web && npm run check`; the
  operator-blind guards stay green.

## Phases

- [x] 1 contracts — a `tasks.openapi.yaml`: the job envelope + **`POST /api/tasks/claim`** (send capability tags
  → atomically lease one claimable job), **`/heartbeat`** (extend the lease), **`/complete`** (mark done +
  optional `sealedResult`), **`/fail`** (attempts++/dead-letter), and **`/enqueue`** (client-originated jobs).
  The **capability-tag registry** as a shared, versioned artifact under `contracts/` (first tag: `grant-delivery`).
  · Done when: contracts lint green; generated clients build on backend + web.
- [x] 2 backend — the **job table** + atomic claim (`SKIP LOCKED`) + lease/TTL/heartbeat + **lazy reclaim**
  (`leased ∧ expiry<now` is claimable) + `attempts`/max → **dead-letter**; the blind envelope (cleartext
  `scopeId`/opaque `capabilityTag`/`resourceRef`; sealed body); the **buy-vs-build** decision resolved here; the
  **`grant-delivery` job type** — **origination** (a grant-less TENANT/GROUP membership ⇒ enqueue a job carrying
  the target `userId` + identity pubkey) + the **completion handler** (accept a `wrappedGrant` onto the target
  membership, **idempotent**). Claim is gated on scope membership; grant completion by **any** member (delivery).
  · Done when: `./gradlew -p backend check` green; tests cover atomic claim, lease-expiry reclaim, dead-letter,
  and `grant-delivery` originated on a **seeded grant-less membership** + completed idempotently by posting a wrap.
- [x] 3 http — a signed happy-path (`http/tasks/…`): seed a grant-less membership → a `grant-delivery` job is
  claimable → claim → (sign a foreign-identity wrap) → complete → the membership now holds a grant; plus
  heartbeat/fail/dead-letter assertions. · Done when: the `.http` response-handler assertions pass against a
  fresh instance.
- [x] 4 web — the **client worker loop**: on scope entry (+ a light poll) the client **claims** `grant-delivery`
  jobs for its scopes, unseals, **wraps `K_SC` to the target pubkey** (`asymWrap` to a foreign identity — new in
  the client), and **completes** (idempotent, first-online-wins). Reuses the cr-0075 open scope (the client
  already holds `K_SC`). · Done when: `npm run check` green; unit tests — the worker claims a `grant-delivery`
  job, produces a foreign-identity wrap that `asymUnwrap`s to the same `K_SC`, completes, and no-ops when the
  member is already granted.
- [x] 5 coverage — map the delivery tests to the **delivery criteria** of
  [uc.spaces.add-member](../docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml)
  (`wrap-to-foreign-grant`, `additive-async-no-revocation`, and the delivery side of `admission-vs-delivery`);
  keep the onboarding criteria (`member-invite-and-onboard`, `scope-member-directory`) as **gaps → cr-0077**;
  the file stays `status: planned`. · Done when: `validate_coverage.py`, `validate_changes.py`,
  `validate_doc_links.py` green.
- [x] 6 verify — the http/backend end-to-end above proves the loop (seed pending membership → job → worker wraps
  → grant delivered → the member opens). A live web smoke lands with [cr-0077](cr-0077-member-add-onboarding.md).
  · Done when: the seeded flow is confirmed green.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | The mechanism + the grant-delivery worker | Status |
|---|---|---|
| backend / http | job store + claim/lease + `grant-delivery` origination & completion | ☑ this CR |
| web | the client worker loop (claim → wrap `K_SC` → complete) | ☑ this CR |
| apple / android | native worker loop | ◻ **deferred (web-first; own CRs)** |

## Definition of done

The backend runs an operator-blind pull queue: workers claim scope-eligible, capability-matched jobs, execute
on-device, and complete idempotently under a lease (crash → lazy reclaim; poison → dead-letter). The
`grant-delivery` job type is originated on a grant-less membership and completed by **any** key-holder's client
wrapping `K_SC` to the target identity — the first wrap to a foreign identity — with the server holding only
opaque envelopes + ciphertext grants. `./gradlew -p backend check` + the `http/` happy-path + `npm run check`
green; the delivery criteria of `uc.spaces.add-member` covered; the three doc validators green. Onboarding that
*produces* pending members is [cr-0077](cr-0077-member-add-onboarding.md).

## Retrospective

Built as specified; two design points were sharpened against the code rather than the plan:

- **Buy-vs-build → the DB's own primitive, not a job framework.** JobRunr / db-scheduler run jobs *on the
  server*, which a blind veyq server cannot do (it holds no key). The workers are **remote key-holders that pull
  over HTTP**, so the claim is a portable **compare-and-set on `status`** (read a claimable candidate → guarded
  `UPDATE … WHERE id=? AND status=<prior>`; first writer wins). It runs identically on the H2 test DB and
  MariaDB, so `./gradlew check` exercises the real claim path; MariaDB `SKIP LOCKED` stays an optional
  throughput layer, not a correctness dependency. Recorded in [background-tasks.md](../docs/crosscutting/background-tasks.md#persistence).
- **grant-delivery has no sealed body; the target rides the claim.** The server *originates* this structural
  job, so it cannot seal a `K_SC` body — and does not need to: everything the worker needs is server-known
  operational metadata. `resourceRef` = the target membership id; the claim resolves the target's userId +
  identity pubkey server-side (no key). `sealedBody` stays the generic extension point for later
  client-originated content jobs. Origination is **reconcile-on-claim** (a key-holder pulling its scope
  materialises jobs for grant-less members) with a `@Scheduled` backstop.
- **Two small enablers the codebase lacked:** `MembershipEntity.wrapped_grant` made **nullable** (the hollow
  "pending member" state ADR 0007 blesses; V21 rebuilds the table — no portable ALTER-nullability across
  H2+MariaDB) and `listMine` now skips grant-less memberships (they stay in the Personal fallback); and a
  `deserializeIdentityPublic` inverse in the web crypto suite, so the client can wrap `K_SC` to a **foreign**
  identity (the first such wrap). A `scopeId` filter was added to `claim` so a worker holding one opened scope's
  key is never handed a job for a scope whose key it lacks.

**Deviation from the plan (Phase 3/5, honest):** the CR asked for a full grant-delivery *signed happy-path* in
`http/` and for `uc.spaces.add-member` to stay `status: planned`. A bare `.http` **cannot** produce a real
scope grant — that needs the ML-KEM `asymWrap`, which the `_lib` signing harness (Ed25519/X25519 only) lacks —
the exact wall `content.http` already hit. So, per the repo's established convention, `http/tasks/tasks.http`
covers the wire-reproducible half (auth boundary, validation, no-grant→204, non-member→403, unknown-job→404,
**verified green live via ijhttp against a fresh Docker instance**), and the full claim→wrap→deliver loop is
proven end-to-end by `TasksSliceTest` (seeds the grant-less membership in-tx, drives it over the HTTP stack).
The coverage file is kept `status: planned` (the onboarding criteria remain gaps → cr-0077) but its **three
delivery criteria are now mapped** — a partially-covered planned file, which `validate_coverage.py` permits.
The live web smoke lands with [cr-0077](cr-0077-member-add-onboarding.md).
