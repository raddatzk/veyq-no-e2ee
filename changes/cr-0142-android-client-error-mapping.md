---
id: cr-0142
title: Android VeyqClient shared error mapping — collapse the ~15× status→error copy-paste (audit P3e, part 1)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

First slice of audit finding #75 (**P3e mobile VeyqClient split**): kill the **~15× status→error copy-paste**
in the Android `:client` — the concrete, named win of P3e — with one shared error-mapping seam. The Android
analog of the web `shared/httpError.ts` shipped in cr-0141 (same audit, same root cause).

Every generated OpenAPI package (`com.veyq.api.setup`, `…auth`, `…account`, `…scopes`, `…content`, `…media`)
ships its **own** `infrastructure.ClientException` with no shared base beyond `RuntimeException`. So each of
the ~15 resource methods in `VeyqClient.kt` hand-wrote `try { call() } catch (e: XxxClientException) { throw
when (e.statusCode) { … } }`, importing five aliased exception types and repeating the `try/catch` scaffold and
the `else -> UnexpectedStatus` tail everywhere.

## Context & constraints

- **New seam:** `android/client/.../ClientErrors.kt` — `clientStatusCode(e): Int?` (the one place that lists
  all six generated `ClientException` types) + `inline fun <T> mapStatus(map: (Int) -> VeyqError, block)` (runs
  the call, translates a client error through the caller's own `when`, re-throws anything that is not a known
  client error — a genuine bug or a 5xx `ServerException` — untouched). Unit-tested (`ClientErrorsTest`).
- **~15 sites converted, mappings byte-identical:** each method keeps its exact `when (status) { … }` (same
  cases, same `else -> UnexpectedStatus(status)`); only the per-package `catch` + `throw` scaffold is gone.
  Sites: login challenge + verify (auth); `fetchNodes`/`postNode`/`patchNode`/`removeNode` (content);
  `fetchScopes`/`postPersonalScope` (scopes); `addDeviceWrap`/`deleteWrap`/`fetchKeyMaterial` (account);
  `startUploads`/`completeMedia`/`fetchNodeMedia`/`presignDownload` (media). `mapMedia(e: MediaClientException)`
  → `mapMedia(status: Int)`, now passed as `::mapMedia`. The five aliased `XxxClientException` imports are
  removed from `VeyqClient.kt` (they live once in `ClientErrors.kt`).
- **Behaviour preserved, verified by the existing domain tests.** `LoginTest`/`ScopesTest`/`ContentTreeTest`/
  `MediaTest`/… already assert specific `VeyqError`s for specific HTTP statuses over MockWebServer — they stay
  green, proving the mappings are unchanged. The `catch (e: Exception) → UnlockFailed/TrustFailed` crypto-failure
  handlers are **not** status→error copy-paste and were left untouched.
- **Net −32 LOC in `VeyqClient.kt`** (52 +/84 −); the god-object shrinks and the error taxonomy is centralised.
- **Deferred to their own CRs (rest of #75), per breadth-first scope discipline:** the **physical file-split**
  of both god-objects into per-domain endpoint-gateways + use-case services (Android `:client` domains:
  auth/unlock+device-trust/scopes/content/media/keymaterial; Apple `VeyqKit`). Apple's status→error is
  **generator-imposed** — swift-openapi emits a distinct `Output` enum per operation, so its `switch out { .ok /
  documented / .undocumented }` has no clean shared helper the way Kotlin's per-package exceptions do; Apple's
  P3e win is therefore purely the structural file-split, deferred with Android's.
- **Note (unrelated, pre-existing):** the reported `android/.../VeyqClient.kt` media-contract-drift build break
  does **not** affect `:client` — it clean-compiles and `:client:test` is green from scratch; the drift (if any)
  is in `:app`, out of scope here.
- **Verify:** `cd android && ./gradlew :client:test`.

## Phases

- [x] 1 android — add `ClientErrors.kt` (`clientStatusCode` + `mapStatus`) + `ClientErrorsTest`; convert the
  ~15 resource methods; drop the five aliased exception imports. · **Done:** `./gradlew :client:test`
  **BUILD SUCCESSFUL** — `:client` compiles clean, **69 tests pass** (2 env-gated skipped), incl. the new
  `ClientErrorsTest` and every existing error-mapping assertion.

## Definition of done

`./gradlew :client:test` green; no per-method `catch (e: XxxClientException)` remains in `VeyqClient.kt`; the
error taxonomy lives once in `ClientErrors.kt`; the shared seam is unit-tested. Operator-blind unaffected (no
wire/crypto change).

## Retrospective

- **Cross-surface audit symmetry.** P3d/P3e share one root cause — the generator emits per-package error types,
  forcing hand-rolled duck-typing. Web got `httpError.ts` (cr-0141); Android now gets `ClientErrors.kt`. Same
  shape, same win.
- **Behaviour-preserving by construction.** Kept every `when` verbatim and leaned on the existing MockWebServer
  domain tests as the proof, rather than rewriting the mappings — so the refactor is provably a no-op on
  behaviour.
- **Honest asymmetry.** Did not force a fake error-helper onto Apple where the generator's per-operation switch
  has no clean seam; Apple's P3e value is the file-split, tracked for its own CR.
