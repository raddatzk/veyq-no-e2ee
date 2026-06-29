---
id: cr-0038
title: Trust-chain & ADR hygiene — dead refs, stale ids, ADR drift, validator hardening
status: done
satisfies: []
adrs: [0001, 0004, 0009, 0013]
invariants: []
---

## Goal

A standing audit ("is everything backed by a use-case or enabler, sufficiently tested, and are the ADRs
still correct?") surfaced a set of **documentation-drift** defects: coverage refs pointing at renamed test
methods, stale doc-ids in backend comments, four ADRs describing a now-different reality, and a CR-number
collision. None touch product behaviour — this CR is pure hygiene + a validator that makes the worst class
of these defects (dead test refs) **fail CI** instead of rotting silently.

This carries no `satisfies:` — it changes no use-case behaviour. It edits docs, comments, and one script.

## Findings & fixes

### 1 — Dead coverage refs (the validator was blind to native/web refs)
- `uc.account.auto-unlock.yaml` + `uc.account.biometric-unlock.yaml` referenced
  `DeviceSecurityStoreTests.testPasswordlessRecordCarriesKeyAndToken` → real method is
  **`testPasswordlessCommitCarriesWrapAndToken`**.
- `uc.account.trust-device.yaml` referenced `DeviceTrustStoreTest.enrolCommitLoadClearRoundTrip` (no such
  class/method) → real is **`DeviceSecurityStoreTest.l1EnrolCommitClearRoundTrip`**.

### 2 — Stale doc-id in backend comments
`uc.spaces.personal-scope` (reclassified to the **enabler** `en.spaces.personal-scope`; the uc doc no longer
exists) was still cited in 6 backend files (`ScopesSliceTest.kt`, `V9__scopes.sql`, `spaces/scope/Entities.kt`,
`ScopesResource.kt`, `ScopesHandler.kt`, `admin/tenant/Entities.kt`). Repointed to `en.spaces.personal-scope`.

### 3 — ADR drift
- **0013** §4: property name `veyq.blob-store.s3-public-endpoint` → **`veyq.blob-store.public-endpoint`**
  (the real `@ConfigProperty` name in `BlobStore.kt`).
- **0009**: ML-KEM-768 was described as "deferred to the first content/scope slice" — it **shipped in
  cr-0025** (swift-crypto `MLKEM768` / BouncyCastle 1.81). Updated to past-tense "shipped".
- **0004**: added an **Implementation** marker — the opaque two-token rotating scheme is built; the **E2EE
  named-sessions half** (`PUT …/sessions/{id}/name` + the unlocked "active sessions" decrypting view,
  `uc.account.sessions`) is **not yet built** (the contract only reserves the family id).
- **0001**: the illustrative tree referenced a non-existent `features/memory/MemoryNodeResource.kt` /
  `NodesApi` / `create-tree` layout → replaced with the real flat `features/content/ContentResource.kt`
  (`ContentApi`) + a note that the rule is the two-layer split, not a fixed sub-folder depth.

### 4 — CR-number collision
`cr-0036-android-memory-shell.md` (untracked, status `planned`) collided with the committed
`cr-0036-settings-usecase-coverage.md`. Renumbered the Android one to **cr-0039** (id field updated).

### 5 — Validator hardening (the highest-leverage fix)
`scripts/validate_coverage.py` only resolved **backend** method refs + **http** file paths; **web / apple /
android** refs were unchecked (a stale comment said "no code yet") — which is exactly why finding #1 rotted
undetected. `check_ref` now resolves **every** surface:
- **web** → file path relative to `web/` must exist;
- **apple / android** → a `/`-bearing ref is a repo-root file path that must exist; otherwise it's
  `ClassName.method` and the method is resolved against that surface's test sources (`apple/Tests` +
  `apple/UITests` swift `func`; `android/**/src/{test,androidTest}` kotlin `fun`, build output excluded).

This immediately caught **three more** fake-green refs in `uc.admin.create-tenant.yaml`: prose
browser-smoke descriptions (`browser-smoke cr-0009 phase 4 …`) sitting in `web: { e2e: [...] }`. Those aren't
automated tests, so they were demoted to comments — the manual smoke is already honestly recorded under
`gaps.web`, and both criteria keep real backend coverage.

### 6 — Apple browse: unit-cover the drill path-stack (real test gap from the audit)
The audit flagged "apple `uc.memory.browse` has no catalogued live UITest". Investigation showed the
**pure** level/breadcrumb logic was covered (`MemoryBrowseTests`) but `HomeModel`'s **drill path-stack**
navigation was not: `goto(crumb)` (truncate the path to a tapped crumb) and `delete(node)` (step back to
the parent when you delete the subtree you're standing in). Extracted both into pure, `Sendable`
`MemoryBrowse.path(_:navigatingTo:)` / `MemoryBrowse.path(_:afterDeleting:)` helpers (parity with the web
`browse.ts`/`location.ts` pattern); `HomeModel` now delegates. Added 5 unit tests — green via `swift test`
(no live instance needed). The genuinely-remaining gap (a live UITest driving the shell UI end-to-end) is
left honestly declared in `gaps.apple`.

### 7 — Admin create-tenant: automate the web Slice-A acceptance (was backend-only + manual smoke)
The audit flagged "admin create-tenant is backend-only" — its only client proof was the manual browser
smoke (the prose refs removed in #5 above). Added **component-level acceptance** at the web UI tier
(happy-dom + `@vue/test-utils`, facade mocked), the same tier as the other web `uc.*` refs:
- `CreateTenantDialog.test.ts` — the create call is shaped correctly (domain lowercased, fields trimmed),
  `created` is emitted + the dialog closes on success, and `domain_taken` surfaces distinctly while the
  dialog stays open.
- `TenantsView.test.ts` — on `created` the view reloads and renders the new tenant as a **PENDING** row
  with the three invite actions; revoke is confirm-gated; a 401 redirects to login; resend delegates.

8 tests, green via `npm run check` (full web suite 129 passing). `uc.admin.create-tenant`'s
`create-pending-tenant` now carries real web refs; `gaps.web` narrows to "Slice B activation UI
(TenantJoinView) still backend-only — its component harness is pending" (it builds an identity + cold
grant, a heavier crypto flow left for its own slice).

### Audit recommendations that turned out NOT to be test gaps (retracted)
Two of the three "fill these gaps" recommendations were, on inspection, **unbuilt features correctly
declared as gaps** — not missing tests. No tests were fabricated for them:
- **Web auto-unlock / device-security:** web device-security is already fully unit-covered under
  `uc.account.trust-device` (`trustDevice.test.ts` + `unlock.device.test.ts`). The open web `gaps` are
  *unbuilt features*: auto-unlock (L3) is "planned, no web level yet"; biometric (L2) is "N/A — web uses a
  WebAuthn passkey, a separate planned use-case".
- **`en.memory.favorites` apple/android tests:** favorites is **web-only built** (apple has only a
  "deferred" comment; android has nothing) — and **enablers carry no coverage YAML by convention** (they're
  not in the validator trust-chain). Nothing to test or catalogue natively yet.

## Definition of done

`python3 scripts/validate_coverage.py` green (11 use-cases) **with all surfaces now resolved**; the hardened
validator rejects the old method names (sanity-checked) and would have failed on findings #1 and the three
admin prose refs. No CR-number collision. ADRs 0001/0004/0009/0013 match the shipped code. No product
behaviour changed.

## Retrospective

- **Assistant — I mislabelled "unbuilt" as "untested".** I handed over a list of "5 test gaps" and the
  human reasonably said "do 1–3". On inspection, 2 of those 3 were *unbuilt features correctly declared as
  gaps*, not missing tests — I'd relayed audit-agent findings ("insufficiently tested") without first
  checking **build status**. Root cause: no triage step separating *built-but-untested* from *not-built-yet*.
  Fix: every gap finding states its build status before it's called a test gap — and "just do them" should
  trigger that triage, not skip it. (The wasted motion here was mine, not the instruction's.)
- **Process — the validator gave false green for ~37 CRs.** The dead refs (#1) and the prose-as-test refs
  (#5) survived because `validate_coverage.py` only checked backend/http; web+native refs were never
  resolved. A guard that silently skips most surfaces is worse than no guard — it reads as "all covered".
  This is the same drift the human had already flagged ("haben wir uns immer um die coverage gekümmert?").
  Root cause: the guard shipped narrower than the thing it claimed to guard, and nobody widened it when
  native/web refs started landing. Fix: landed here; the lesson is to distrust a green check whose scope
  you haven't re-read.
- **Process (shared, human owns the workflow) — hygiene was reactive, not gated.** ADR drift (0001/0004/
  0009/0013), stale doc-ids, a duplicate CR number, stale code comments (`macOS 13/iOS 16`) all accumulated
  because nothing touches the relevant ADR / re-checks the CR number / re-reads comments when code changes.
  An audit cleaned it in bulk; cheaper would be a per-CR "does this change an ADR's reality?" prompt + a
  trivial CR-number collision check. Worth deciding whether that's enforced tooling or just discipline.
