---
id: cr-0128
title: Backend cleanups — remove dead WrapsHandler + valueOf→decode on generated enums
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Two safe backend cleanups from the code-quality audit. (1) Delete `WrapsHandler` — a dead class (zero
references) that duplicated `KeyMaterialHandler.addWrap/deleteWrap` and, worse, read the bearer token by hand
(`sessions.resolveUserId(accessToken)`), the exact pattern centralized-http-auth (ADR 0008) forbids; its KDoc
also claimed a responsibility another class actually fills, so a reader landing in the keymaterial slice saw
two contradictory "add/remove wrap" handlers. (2) Replace `Enum.valueOf(...)` with the generated drift-safe
`Enum.decode(...)` on the nine sites that map a stored string to a generated enum, so a future wire-value drift
fails with a clear `Unknown … value` instead of silently matching (or missing) a constant name — aligning them
with the siblings that already use `decode()`.

## Context & constraints

- **Dead code, verified:** `grep -rn WrapsHandler backend/src` returns only its own class declaration — no call
  site anywhere; the live resource (`AccountWrapsResource`) uses `KeyMaterialHandler`. `./gradlew check` after
  deletion confirms nothing referenced it.
- **`decode()` exists for every converted enum, verified against the generated model** (`fun decode` present in
  `ScopeKind`, `NodeKind`, `InviteStatus`, `InviteRole`, `TenantStatus` — and already used for `ShareRole`,
  `MediaDerivativeKind`, `NodeKind`). Converted sites: `ScopesResource` (`ScopeKind` ×1), `ContentResource`
  (`NodeKind` ×1), `TenantsResource` (`InviteStatus` ×5, `InviteRole` ×1, `TenantStatus` ×1).
- **Left as-is, on purpose:** `SharingResource:181` `ShareMediaDerivative.Kind.valueOf(...)` — a **nested** enum
  with no `decode()`, because the derivative-kind is modeled twice in the contract (`ShareMediaDerivative.Kind`
  vs the top-level `MediaDerivativeKind`). Unifying them is a contract change, not a mechanical swap — a
  contract-level follow-up, not this CR.
- **Out of scope (deferred to a separate CR):** the two larger P2c refactors — the `owner_key` string-union →
  `sealed interface`, and extracting the FK-ordered media-purge duplicated ×3. Those are structural refactors,
  not the safe mechanical cleanups this CR covers.
- **Verify:** `cd backend && ./gradlew check` + the trust-chain guards.

## Phases

- [x] 1 backend — deleted `features/account/keymaterial/WrapsHandler.kt`; `valueOf`→`decode` on the nine
  top-level-enum map sites in `ScopesResource`, `ContentResource`, `TenantsResource`. · **Done:** `./gradlew
  check` **BUILD SUCCESSFUL in 35s** (compile confirms every `decode()` resolves + nothing referenced the
  deleted class); all three trust-chain guards green.

## Definition of done

`./gradlew check` green; no `WrapsHandler`; the nine converted sites use `decode()`; trust-chain guards green.

## Retrospective

- **Scope discipline:** the P2c bundle listed four items; this CR consciously shipped only the two *safe,
  mechanical* ones (dead-code delete + drift-safe enum decode) and left the two *structural* refactors
  (owner_key sealed interface, purge dedup) for their own CR, rather than half-doing all four.
- **Verification, not inheritance:** confirmed `decode()` exists per enum in the generated model before
  swapping, and left the one nested enum that genuinely lacks it — so the build passed first try.
- **Next time:** nothing notable went wrong; a clean mechanical CR.
