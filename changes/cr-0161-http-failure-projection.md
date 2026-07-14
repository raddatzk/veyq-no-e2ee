---
id: cr-0161
title: HTTP failure projection → shared/http/ — one envelope writer + self-describing Outcome failure variants (audit P3b, ADR 0042)
status: done
satisfies: []
adrs: ["0042"]
invariants: []
---

## Goal

Implement the projection decision recorded in [ADR 0042](../docs/decisions/0042-http-failure-projection.md):
centralize how backend `…Outcome` **failure variants** project to the wire `ErrorResponse{code}`, **without**
deleting the sealed types (audit finding **P3b**, [inbox #72](../docs/inbox.md)). Today the generated
`ErrorResponse` envelope is constructed in **4 copy-pasted idioms across 17 sites**, and each of the **16
`…Resource`** classes re-derives the `variant → (code, HTTP status)` mapping in its own `when` — a mapping that
has **drifted** (the same "caller is not a member of the scope" is 404 in content/media/favorites but 403 in
`scopes`/`tasks`). This is a **behaviour-preserving refactor**: every existing case keeps its exact HTTP status +
error `code` (including the 3 drifted 403s — the operator-blind fix is the separate [inbox #83](../docs/inbox.md)).
`./gradlew check` is the proof.

## Context & constraints

- Realises **ADR 0042** (`adrs: ["0042"]`). Pure backend refactor: **no use-case satisfied**, no contract change
  (`ErrorResponse{code}` already exists in all 9 specs, guarded by cr-0144/ADR 0038), no crypto change, no
  migration.
- **Keep the sealed `…Outcome` types** (68 types, ~145 failure variants) — they give compile-time exhaustiveness,
  self-documenting handler signatures. The **variant count is not the target**; the un-centralized *projection*
  is. The finding's "collapse ~70 variants / 15× envelope in 4 shapes" is corrected by the inventory below.
- **`shared/http/` is a leaf** (per ADR 0001 / styleguide §1) — it already holds `BearerToken.kt`. The new
  `Failure`/`HttpFailure` type + status-bucket table + envelope writer + `Failures` catalog live here; it must not
  import a feature.
- **Behaviour-preserving = same (status, code) for every mapped variant.** The **sole** regression guard is the
  HTTP layer: the backend test suite has **zero** handler/Outcome unit tests — every test is a `@QuarkusTest`
  REST-assured integration test. **540 `.statusCode(N)` + 140 `.body("code", equalTo(…))` assertions across 33
  files** pin the contract; keeping the sealed types is invisible to them. So run the **full** `./gradlew check`,
  not a narrow subset.
- **Operator-blind convention is recorded but NOT applied here.** ADR 0042 fixes the correct status of a
  non-member to 404; cr-0161 **preserves** the current 3× 403 (it only makes the drift *visible* as a
  FORBIDDEN-tagged variant). The flip is [inbox #83](../docs/inbox.md).

### The duplication to collapse

- **4 envelope-construction idioms → one shared writer.** All build the same `ErrorResponse(code=…)`:
  (1) overloaded `private fun error(status, code)` — `ContentResource`, `MediaResource`, `BucketsResource`,
  `SharingResource`, `FavoritesResource`; (2) named helpers `forbidden`/`notFound`/`conflict`
  (`ScopesResource`) + `inviteNotFound`/`notTenantOwner`/… (`TenantsResource`); (3) fully-inline
  `Response.status(…).entity(ErrorResponse(code=…))` — `TasksResource`, `AccountWrapsResource`,
  `AccountProfileResource`, `AccountIdentityPinsResource`, `SetupResource`, `TokenResource`, `LoginResource`,
  `AccountResource`; (4) the 2 central JAX-RS providers `shared/auth/AuthExceptionMappers` +
  `shared/ratelimit/AuthRateLimitFilter`, which also build `ErrorResponse` inline.
- **~16 per-resource `variant → (code, status)` `when` ladders → self-describing variants.** Each failure variant
  gains its `code` + status bucket (recurring failures — `scope_not_found`, `not_scope_member`, `slug_taken`,
  `already_member`, … — via the shared `Failures` catalog); each resource `when` collapses to
  `is <Success> → 2xx …; is HttpFailure → writer(outcome)`.
- **Status buckets (`FailureStatus`, defined once in `shared/http/`):** the Outcome-failure statuses
  BAD_REQUEST→400, FORBIDDEN→403, NOT_FOUND→404, CONFLICT→409, UNPROCESSABLE→422, RATE_LIMITED→429, plus the tail
  the central providers + non-Outcome error sites emit — UNAUTHORIZED→401 (auth mapper), GONE→410 (dead invite),
  SERVER_ERROR→500 (`AccountResource` internal inconsistency), SERVICE_UNAVAILABLE→503 (`StoreUnavailable`). The
  last four were **not** in the refine's "~6 buckets" estimate — they surfaced from the providers + inline error
  sites at implement time (see Retrospective).

### Producers vs. consumers (inventory)

- **Producers (Outcomes):** the 14 `…Handler.kt` (`content/NodesHandler`, `content/favorites/FavoritesHandler`,
  `media/MediaHandler`, `sharing/SharingHandler`, `spaces/scope/ScopesHandler`, `admin/tenant/TenantHandler` +
  `TenantAcceptHandler` + `TenantTermsHandler`, `account/keymaterial/KeyMaterialHandler`,
  `account/profile/AccountProfileHandler`) **plus** `tasks/TasksStore.kt` + `account/identitypins/IdentityPinsStore.kt`
  (which declare their own results). Each failure variant is tagged with its `HttpFailure` projection.
- **Consumers (mappers to collapse):** the 16 `…Resource.kt` + the 2 central providers. Every `when(outcome) →
  Response` and every envelope helper lives here.
- **Out of the envelope path (leave untouched):** the per-item batch errors (`Media`/`Sharing` return HTTP 200
  with an `error: String?` per item — `UploadItemResult.error` etc.) are **success** responses, not the
  `ErrorResponse` envelope; and `ResolveShareOutcome.Locked` / `ListMediaOutcome.NotModified` are non-error
  shell/304 variants. None route through the failure writer.

> **Blast-radius note (per the cr-0160 lesson — grep the test trees):** no test references any `…Outcome` type,
> so the "variants stay" half touches **zero** tests; the rewire is validated **only** by the 540+140 HTTP
> assertions. There is **no** shared test error-envelope helper — each assertion is inline, so nothing shared
> breaks. Implementation discovers the exact variant/site count from the build; this CR fixes decisions, not
> line edits.

## Phases

- [x] 1 backend (shared primitive) — created the `shared/http/` failure projection: `HttpFailure` interface
      (`code` + status bucket), the `FailureStatus` bucket→HTTP-status table, a single envelope **writer**
      `httpError(HttpFailure)`, and a `Failures` catalog for the recurring semantic failures. Folded the 2 central
      providers (`AuthExceptionMappers`, `AuthRateLimitFilter`) onto the writer so `ErrorResponse(` is built in
      exactly one place · Done: `./gradlew check` green; the primitive is a leaf (no feature import).
- [x] 2 backend (rewire) — tagged every `…Outcome` failure variant with its `HttpFailure` projection (its existing
      `code` + the bucket matching its **current** status, incl. the 3 preserved 403s), collapsed each resource
      `when` ladder to `is <Success> → 2xx …; is HttpFailure → httpError(o)`, deleted the per-resource
      `error`/`forbidden`/`notFound`/`conflict`/`inviteGone`/`profileNotFound` envelope helpers · Done:
      `./gradlew check` green (all 540 status + 140 code assertions unchanged → behaviour-preserving); no
      `…Resource` constructs `ErrorResponse` or keeps an envelope helper.

## Out of scope

- **The operator-blind status fix** (non-member 403 → 404 at `scopes.epochs`/`scopes.listMembers`/`tasks.createJob`)
  — deliberately deferred to [inbox #83](../docs/inbox.md); a security-reviewed behaviour change. cr-0161 only
  makes that drift *visible* as a FORBIDDEN-tagged variant.
- **Collapsing the sealed `…Outcome` types themselves** — ADR 0042 keeps them (rejected the `Either<Failure,
  Success>` form).
- **Per-item batch errors** (`Media`/`Sharing` 200-with-`error`) and the non-error `Locked`/`NotModified` shell
  variants — not the `ErrorResponse` envelope.
- **No contract/spec change** (`ErrorResponse` already canonical) and **no coverage-YAML change** (test method
  names unchanged; every existing ref still resolves).

## Verify

- `./gradlew check` (in `backend/`) **green** after each phase — the full `@QuarkusTest` suite (540 status + 140
  error-code assertions across 33 files) is the behaviour-preserving proof that every variant still projects to
  the identical `(status, code)`.
- **Enforcement guard (permanent CI check):** `scripts/validate_http_failure_projection.py` asserts `ErrorResponse(`
  is constructed in **exactly one** file — the `shared/http/HttpFailure.kt` writer — and fails on any other
  `backend/src/main` construction site; wired into `ci.yml`'s guard step; **drift-injection tested** (an injected
  `ErrorResponse(...)` in a resource reds it). Replaces the one-shot grep.
- `python3 scripts/validate_changes.py` + `python3 scripts/validate_doc_links.py` green (this CR + ADR 0042 +
  the styleguide §13 pointer resolve).

## Definition of done

`shared/http/` owns the single `HttpFailure` projection (interface + status-bucket table + envelope writer +
`Failures` catalog); `ErrorResponse` is constructed in exactly that one file; every `…Outcome` failure variant
self-describes its `(code, bucket)` and each of the 16 resources routes failures through the shared writer with no
local envelope helper; the 2 central providers render through it too. Behaviour is unchanged — `./gradlew check`
green (540+140 HTTP assertions), including the 3 preserved 403s. The enforcement grep is added as a permanent
guard. ADR 0042 + styleguide §13 pointer in place; inbox **#72 → done → cr-0161** and the operator-blind fix
tracked as **#83**.

## Retrospective

Done, behaviour-preserving — `./gradlew check` green (540 status + 140 code REST-assured assertions unchanged),
`ErrorResponse(` now built in exactly one file, the guard script red-on-drift. ~145 failure variants across 68
Outcome types + the 2 central providers now route through the shared `httpError` writer. Three things the plan
under-counted, all surfaced by the real code, not scope creep:

- **The bucket set was bigger than "the ~6 Outcomes use".** The refine listed 6 status buckets from the Outcome
  mappings, but folding in the central providers + the long tail of resources revealed **four more**: 401
  (`AuthExceptionMappers`), 410 GONE (`invite_no_longer_valid`), 500 (`AccountResource.internal_error`, ×2), and
  503 (`ShareBlobOutcome.StoreUnavailable`). Each was a genuine status the codebase already emitted; the plan's
  "~6 buckets" was an undercount because it only sampled the `…Outcome` failures, not the ExceptionMapper/filter
  and the non-Outcome inline error sites (500/503). Lesson for the next projection-style refine: inventory the
  **response-status space** end-to-end (every `Response.status(...)` / `serverError()` / thrown mapper), not just
  the sealed-Outcome failures — the tail statuses live outside the Outcomes.

- **`ErrorResponse` escaped the Outcomes at ~6 non-variant sites.** login/token/setup (`authentication_failed`,
  `invalid_request`, `refresh_rejected`, `setup_already_complete`), tasks' non-member `null → 403`, and the
  `AccountResource` 500s build the envelope from a `null`/Boolean/thrown-exception, not a sealed variant. These
  still routed cleanly through `httpError(httpFailure(...))` / `httpError(Failures.x)` — the writer works for a
  bare failure just as well as a tagged variant — but the CR's inventory ("producers = the handlers") missed
  them. Lesson: the projection's consumer surface is "every error `Response` a resource can return", which is a
  superset of "every Outcome failure variant".

- **Cross-slice catalog consolidation was a second, deliberate pass.** The six seeded `Failures` entries covered
  the obvious cross-cutting failures; the feature sweep then showed 8 more codes recurring across ≥2 slices
  (`tenant_not_found`, `member_not_found`, `already_member`, `last_owner`, `capability_disabled`, `username_taken`,
  `invalid_request`, `rate_limited`). Left inline they'd reintroduce exactly the cross-slice drift the ADR forbids,
  so they were promoted to the catalog and the ~28 sites repointed (a scripted, byte-identical replacement — code
  + status unchanged, so `check` fully covers it). Feature-**local** high-count codes (`share_not_found` ×7,
  `not_scope_owner` ×6, `invite_not_found` ×5) were deliberately left inline: a global catalog is for cross-slice
  failures, not one slice's private vocabulary.

**Method note (worked well):** the content pilot compiled *before* fanning out proved the load-bearing assumption
— Kotlin proves `when` exhaustiveness with `is HttpFailure` (a plain interface) when every non-success sealed
subtype implements it, so no `else` is needed and the exhaustiveness guarantee is preserved. Had that failed, the
whole shape would have changed (nested-sealed `Failure`), so pinning it on one feature before the 6-way fan-out
was the right sequencing. Delegating the 6 feature clusters to parallel subagents with a byte-identical spec +
mandatory "report your (code,status) table" kept the main context lean and the mapping auditable; the 3 buckets
they couldn't resolve (410/500/503) were exactly the ones they correctly flagged rather than guessed.

**Process retro (spoken separately):** two-directional points for how we work — see the session hand-off.
