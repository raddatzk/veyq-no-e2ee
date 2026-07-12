---
id: cr-0141
title: Web shared httpError decoder — replace the dead apiError.ts + unify 8 hand-rolled decoders (audit P3d, part 1)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

First slice of audit finding #74 (**P3d web async primitives**): give the web client **one** decoder for the
failures thrown by the generated OpenAPI clients, and retire the dead `shared/apiError.ts`.

Every generated package (`generated/auth`, `generated/account`, `generated/scopes`, …) ships its **own**
`ResponseError` class, so `err instanceof ResponseError` from one package is `false` for an error thrown by
another. Eight call sites worked around this by hand-duck-typing the same shape (`(e as {response?: Response})
.response`, `.clone().json()` for a `.code`, map the status) — the same ~12 lines copied around. The one file
that was *meant* to centralise this, `shared/apiError.ts`, had zero importers and the **wrong shape** (it took a
`Response`; every live decoder takes a thrown error), so it was never adopted — dead code the audit called out.

## Context & constraints

- **New primitive:** `src/shared/httpError.ts` — `decodeHttpError(e) → {status, code}` (async, clones the body
  solely for its machine-readable `code`, never surfaces it verbatim — the same warning the retired file
  carried), plus sync predicates `httpStatus(e)`, `isHttpStatus(e, status)`, `isUnauthorized(e)`, and
  `isCodedError(e, status, code)`. All duck-type the shared `.response` shape, so one decoder serves every
  generated package. Unit-tested (`httpError.test.ts`, 6 cases incl. empty/non-JSON body, non-HTTP failure,
  status short-circuit).
- **Deleted:** `src/shared/apiError.ts` — verified zero importers (`grep apiError src` → only its own file).
- **8 decoders now delegate** (behaviour preserved, names + domain docs kept so call sites still read in domain
  terms):
  - `features/spaces/members.ts` `memberError` → `decodeHttpError`
  - `features/admin/createTenant/tenants.ts` `tenantError` → `decodeHttpError` (drops its now-unused
    `ResponseError` import)
  - `features/spaces/scopes.ts` `isStaleEpoch` → `isCodedError(e, 409, 'stale_epoch')`
  - `features/account/identityPins.ts` `isStaleVersion` → `isCodedError(e, 409, 'stale_version')`
  - `features/memory/share.ts` `isStatus(e, 403)` → `isHttpStatus`
  - `app/activeSpace.ts` `isNoTenant` → `isHttpStatus(e, 404)`
  - `features/account/login/login.ts` `isUnauthorized` → shared `isUnauthorized` (the `withRefresh` hot path;
    drops the redundant `name === 'ResponseError'` check, aligning it with every other decoder — functionally
    identical since only the generated `ResponseError`s carry a `.response`)
  - `features/memory/media.ts` inline `instanceof ResponseError && status === 304` → `isHttpStatus(e, 304)`
    (drops its now-unused `ResponseError` import)
- **Net −49 lines**; the duck-typing rationale that was copied into `login.ts` now lives once in `httpError.ts`.
- **Deferred to their own CRs (rest of #74), to avoid half-doing all of P3d in one pass** (the cr-0128 scope
  discipline): the `useAsyncData` fetch-state primitive (~23 sites, 3 shapes), the `handle401` login-**redirect**
  helper + its ~18 inline adoptions (lands with the member-panel dedup so those files churn once), `useToast` /
  `useUndo`, `useObjectUrls`, and the member-panel + share-viewer-thumbnail-pipeline dedups. This CR ships the
  data-layer foundation (`isUnauthorized` is the *detection* half of `handle401`) the rest build on.
- **Verify:** `cd web && npm run check` (lint + typecheck + test + build).

## Phases

- [x] 1 web — add `shared/httpError.ts` + test; delete dead `shared/apiError.ts`; delegate the 8 decoders.
  · **Done:** `npm run check` green — eslint clean, `vue-tsc` clean, **581 tests pass** (4 e2e skipped, incl.
  the new `httpError.test.ts`), build OK.

## Definition of done

`npm run check` green; `shared/apiError.ts` gone; the 8 decoders delegate to `shared/httpError.ts`; the new
primitive is unit-tested. Operator-blind unaffected — error bodies are still read only for their `code`.

## Retrospective

- **Foundation before consumers.** Started #74 with the one primitive every other part depends on (a 401/coded
  decoder), adopted it in all 8 existing decoders in the same CR so it can't rot into dead code the way
  `apiError.ts` did — rather than adding an unused abstraction.
- **Scope discipline.** Consciously shipped only the decoder (data-layer, fully unit-testable, no UI churn) and
  left the fetch-state / toast / object-URL / panel-dedup parts of P3d for their own CRs — the inbox note tracks
  the remainder.
