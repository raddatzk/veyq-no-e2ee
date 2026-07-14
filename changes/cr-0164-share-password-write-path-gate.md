---
id: cr-0164
title: The share-password gate fronts the guest WRITE path too — close the contribute-without-the-password bypass
status: done
satisfies: ["uc.sharing.share-password"]
adrs: ["0037", "0030", "0031"]
invariants: [operator-blind-e2ee, least-privilege]
---

## Goal

Fix an **access-control bypass**: the share-password gate (`passwordCheck`) is called on the two **read** paths
only. The four **guest-write** ops — `createShareIdentity` · `startShareBlobUploads` · `completeShareMedia` ·
`deleteShareMedia` — never check it. They gate on a `contributorToken` instead, but `createShareIdentity`
**mints that token for anyone holding the shareId** (which is the link), so the token proves nothing about the
password. A link-holder who does not know the password can contribute to a password-protected share.

For an **UPLOADER** share the password is thereby *functionless*: a write-only drop-box has no read path at all
([ADR 0031](../docs/decisions/0031-uploader-public-share.md)), so the gate protects nothing it does.

**The state docs are already right — the implementation never caught up.**
[`uc.sharing.share-password`](../docs/features/sharing/use-cases/uc.sharing.share-password.md) specifies exactly
this ("*Every subsequent guest call (blob GET, contribute/upload) carries the token too*" · "*The gate fronts every
guest endpoint, so a VIEWER, CONTRIBUTOR or UPLOADER share is protected*"), and so does
[cr-0118](cr-0118-password-protected-shares.md) ("*contributor/uploader endpoints all require it*"). No decision
is re-opened here; this CR makes the code do what the UC says.

Found during the 2026-07-14 docs-vs-implementation review. Sibling: [cr-0165](cr-0165-share-password-attempt-bound.md)
(same feature, **different defect class** — that one fixes a missing *bound*, this one a missing *gate*).

## Context & constraints

- **Read is not affected and stays closed.** `resolve` and `resolveBlob` already call `passwordCheck`
  (`SharingHandler.kt` — the two call sites). **There is no data leak**; what is broken is the *access*
  promise: a curator who sets a password expects only password-holders to contribute.
- **Why the token cannot stand in for the password** (this is the load-bearing argument for decision 1):
  `contributorToken` has **no lifecycle** — it is minted once and never expires or rotates. ADR 0037 explicitly
  allows adding a password to a *live* share ("*set/change/remove in place … the same link keeps working*"). If
  the token were the password proof, every token minted **before** the password was set would keep its write
  access forever. On the read side that hole does not exist, because `resolve` re-checks on every call.
- **The server never sees the password** ([ADR 0037](../docs/decisions/0037-password-protected-shares.md)): the
  client derives `pwToken = Argon2id(password, pw_salt)`, the server stores and compares only
  `SHA-256(pwToken)`. This CR adds **no** crypto and does not change that.
- **Contract change** — 4 ops gain the existing `SharePassword` header parameter. So the phase seam is
  `contracts → backend → web`. A contract change does **not** ride the `quarkusDev` hot-reload (codegen does not
  re-run), so the live smoke needs a backend restart — the trap cr-0118 and cr-0119 both hit.
- **No migration** (see `## Migration`).

## Decisions

1. **The check is uniform: every guest call, read and write.** `passwordCheck` runs in all four write handlers,
   exactly as it does in `resolve`/`resolveBlob`. **The token says *who*; the password says *whether*** — two
   questions, both answered on every call.
   *Rejected — gate only `createShareIdentity` and let the minted token carry the proof.* Saves three header
   parameters and a web change. But it re-introduces the lifecycle hole above (pre-password tokens keep writing,
   forever), makes write inconsistent with read, and contradicts the UC's own wording. A cheaper gate that is
   wrong on the flow ADR 0037 explicitly supports is not cheaper.

2. **Refusal is projected as the read path already projects it** ([ADR 0042](../docs/decisions/0042-http-failure-projection.md)):
   missing token → the same non-enumerable **404** the unknown-share case returns; wrong token → **403
   `share_password_incorrect`**, the code that already exists. No new code, no new status.
   *Note for implement:* `resolve` maps MISSING → `Locked(share)` (it serves the challenge shell — the guest
   needs `pw_salt`), while `resolveBlob` maps MISSING → `NotFound`. **The write path follows `resolveBlob`, not
   `resolve`**: there is no challenge to serve on a write, and the salt is already obtainable from the locked
   resolve.

3. **Membership of the check stays where it is.** `passwordCheck` is `private` in `SharingHandler` and all six
   call sites live in that one class — no extraction to `shared/`, no new abstraction. Six usages of a
   3-line private helper in a single class is not a shape problem.

## Inventory

Every count below is the output of the command next to it, run at `b9cdf17b`. Line numbers deliberately omitted
(they drift); the symbols are greppable.

| Surface | Sites | Command that produced the count |
|---|---|---|
| `contracts/api/sharing.openapi.yaml` | **4** ops gain `- $ref: "#/components/parameters/SharePassword"` (`createShareIdentity` · `startShareBlobUploads` · `completeShareMedia` · `deleteShareMedia`) | `grep -nE 'operationId: (createShareIdentity\|startShareBlobUploads\|completeShareMedia\|deleteShareMedia)' contracts/api/sharing.openapi.yaml` → 4 |
| — the parameter already exists, referenced **2×** today | `resolveShare`, `resolveShareBlob` | `grep -c 'parameters/SharePassword' contracts/api/sharing.openapi.yaml` → 2 |
| `SharingHandler` | **4** fns gain the `pwToken` param + the `passwordCheck` guard (`createIdentity` · `startUploads` · `completeUploads` · `deleteMedia`) | `grep -nE 'fun (createIdentity\|startUploads\|completeUploads\|deleteMedia)\(' …/SharingHandler.kt` → 4 |
| `SharingResource` | **4** `override fun`s thread the generated header param through | `grep -nE 'override fun (createShareIdentity\|startShareBlobUploads\|completeShareMedia\|deleteShareMedia)' …/SharingResource.kt` → 4 |
| `web/src/features/memory/share.ts` | **4** call sites in **3** exported fns (`createContributorIdentity` · `addSharePhoto` · `deleteSharePhoto`) | `grep -rnE '\.(createShareIdentity\|startShareBlobUploads\|completeShareMedia\|deleteShareMedia)\(' web/src` → 4 |

**The web plumbing already exists — follow it, don't invent it.** `share.ts` threads the token as a trailing
optional param on the read path (`resolveShare(shareId, fragmentKey, pwToken?)`,
`openShareGrid/Lightbox/Original(shareId, si, pwToken?)`). The three contributor fns take the same
`pwToken?` param. Verify the caller actually has it in scope at those sites — `ShareView.vue` holds the unlocked
token for the read path; implement must confirm it reaches the contribute path rather than assume it.

**Test blast radius — expected zero flips.** Keyed on **which share a test seeds** (per cr-0162's retro: the
radius keys on the actor/fixture, not on the asserted code):

| Test | Seeds | Flips? |
|---|---|---|
| `ShareContributorSliceTest`, `ShareUploaderSliceTest`, `ShareNoteSliceTest` | shares **without** a password | **no** — `passwordCheck` returns `OK` on `!passwordRequired` before touching the token |
| `SharePasswordSliceTest` | password shares, but drives **only read** ops — all **5** `@Test` methods (`grep -c '@Test' …SharePasswordSliceTest.kt` → 5) are resolve/blob/manage | **no** |

`grep -rln 'passwordRequired.*true\|createWithPassword' backend/src/test/kotlin` → **1 file** (`SharePasswordSliceTest`).
**That absence is the finding**: no test seeds a *contributor on a password share*, which is exactly why the
suite never saw this. **If an existing assertion flips, stop** — it means a legitimate contributor lost access
and the guard is wrong.

## Surface impact

- **web** — the three contributor fns pass the token. A guest who unlocked the share already holds it; no new
  prompt, no UX change.
- **apple / android** — parked (web-first V1); neither implements the guest share path.

## Plan

1. **contracts** — the 4 ops reference the existing `SharePassword` parameter; document the 403 on each, mirroring
   `resolveShare`. Done when: `./gradlew openApiGenerateSpecs` green (per cr-0162's retro this generator — not
   `validate_contracts_common.py` — is the real gate for YAML shape errors).
2. **backend** — `passwordCheck` in the 4 handlers + the 4 resource adapters threading the header.
   Done when: `./gradlew check` green **with no existing assertion changed**.
3. **web** — `pwToken?` on the 3 exported fns + their call sites. Done when: `npm run check` green.
4. **the guard + coverage** — `SharePasswordWriteGateTest` (below) + the `unlock-and-gate-endpoints` criterion of
   `uc.sharing.share-password` gains refs to it. Done when: `python3 scripts/validate_coverage.py` green.

## Verify

- `./gradlew cleanTest check` in `backend/` — green, **no existing assertion touched**.
- **Enforcement guard (permanent, carries the invariant):** a table-driven `SharePasswordWriteGateTest` seeding a
  **CONTRIBUTOR share with a password** and firing **all four** write ops with (a) no token → 404, (b) a wrong
  token → 403 `share_password_incorrect`, (c) a valid token → the op succeeds. Plus the same table for an
  **UPLOADER** share — the role for which the gate is functionless today. **Verified to bite:** reverting any one
  handler's check must red that row specifically. Mirrors `OperatorBlindProbeTest` (cr-0162) in shape.
- The existing `SharePasswordSliceTest` (4 read-path methods) stays green untouched.
- Live check on `veyq.localhost:8081` **after a backend restart** (contract change ≠ hot-reload): a
  password-protected contributor share still accepts an upload from an unlocked guest; a direct
  `POST /api/shares/{id}/identities` **without** the header is refused.
- `python3 scripts/validate_changes.py` + `validate_doc_links.py` + `validate_coverage.py` green.

## Migration

**None.** The gate is evaluated per request; nothing wrong is persisted. Media that a link-holder contributed
without the password would be real, but there is no production instance and no audit log — nothing to
reconstruct. Recorded so the question is not silently re-opened.

## Definition of done

- No guest-write op serves a password-protected share without a valid token — proven by
  `SharePasswordWriteGateTest` over the full 4-op × 3-case table on **both** contributable roles, not by inspection.
- An UPLOADER share's password is load-bearing rather than decorative.
- A legitimate unlocked contributor's flow is unchanged (no existing assertion flipped, live smoke green).
- The UC needs **no edit** — it already specifies this. If implement finds the UC wrong on any detail, that is a
  finding: stop and reconcile rather than bending the code to the doc.

## Retrospective

Hermetically done: `contracts → backend → web → guard` all green. `./gradlew cleanTest check` green (45 sharing
tests, 0 flips — the CR's stop-signal never fired); `SharePasswordWriteGateTest` 2/2 and **verified to bite**
(removing the `createIdentity` gate reds both rows); `npm run check` green (636 web tests, lint+build clean); all
six trust-chain guards green. **Live smoke passed** (user-run, after a backend restart — a contract change can't
ride the `quarkusDev` hot-reload, codegen doesn't re-run): a password-protected contributor share still accepts an
unlocked guest's upload, and a direct `POST …/identities` without the header is refused.

- **I reproduced cr-0158's exact `cp -i` trap — live, in the same session that analysed it.** The bite-proof's
  restore (`cp /tmp/bak …`) hit the interactive `cp -i` overwrite prompt and hung, leaving the handler in its
  *injected* (broken) state; I only caught it because I grepped the gate-marker afterwards. cr-0158's retro had
  recorded this verbatim ("prefer `git checkout --` over `cp` on this machine") — and it was part of *my own*
  review earlier this session. Root cause: I packed inject + test + restore into one background bash block, so the
  restore ran unattended where an interactive prompt is fatal. **Next time:** the bite-injection restore is `git
  checkout -- <file>` (or `git stash`), never `cp`, and it is a *separate* verified step, not tail-ended onto the
  test run. The `command cp -f` recovery worked, but the right fix is to not use `cp` at all.
- **The blast-radius prediction held exactly — because the inventory keyed on the right axis.** The CR said "zero
  test flips" reasoning that the tests drive over HTTP, not the handler signature; adding a `pwToken` param to four
  handlers broke **no** test call. This is the refinement checklist working: the inventory that greps for *how the
  tests reach the code* (HTTP vs. direct call) predicted the radius correctly, where cr-0160/cr-0162 undercounted
  by greping the wrong axis.
- **Two pre-existing noise sources cost diagnosis time; both were disproved by a baseline, not assumed away.** A
  backend H2 `SessionPurge` shutdown stacktrace and a web vitest `EnvironmentTeardownError`/`ECONNREFUSED:3000`
  both appeared in a *green* run. Rather than wave them off (or panic), I proved each pre-existing: the web one by
  a `git stash` baseline (exit 0 with the same ECONNREFUSED) plus two clean re-runs (flaky teardown race). That is
  the cr-0108/cr-0134 discipline paying off — but it flags that the repo's test **shutdown noise** slows every
  verification. Worth an inbox item (a flaky-teardown / ECONNREFUSED-on-3000 cleanup).
- **What the CR under-specified, caught cheaply at implement:** the guard *ordering* (`passwordCheck` before
  `canUpload`, so a password share leaks no role signal without the password) was only implicit in the CR
  ("follows `resolveBlob`"). It follows from ADR 0042 boundary-first, but a design-bearing ordering deserves to be
  a named decision, not inferred. Minor — it was the right call and the bite-test pins it.
- **Process win worth naming:** the three wrong numbers I'd written into this CR (the `8-line class`, the `2 hits`,
  the `4 test methods`) were caught by *my own* pre-build claim-check, before a line of code — exactly the "verify
  your own claims, and a null grep is only a finding once you've searched the right place" rule this session is
  built around. The `@Test`-vs-`fun` regex miss (a name with digits) is the concrete lesson: count at the marker,
  not the name.
