---
id: cr-0178
title: Sweep the dangling ADR 0009 citations — repoint to the surviving ADR 0017
status: done
adrs: ["0017"]
invariants:
  - operator-blind E2EE — comment-only + a spec description; no wire, no crypto, no semantics change
  - API-first — the fix is in the contract; the generated client is regenerated, never hand-edited
---

## Goal

Close inbox **#95 (c)**: the native drop (`7a1c4e9f`, 2026-07-15) deleted **ADR 0009**
(*native-crypto-per-platform-vectors*) but left every citation of it dangling. Repoint them all to the
surviving home, **ADR 0017** (*crypto primitives & post-quantum strategy*), which is where the drop commit
moved the conformance vectors.

## Why ADR 0017 is the right target (verified, not guessed)

The user's guard: only repoint if 0017 actually carries what 0009 was cited for. It does.

- Every citation is about the **wrap primitive / conformance vectors**: "`asymWrap`, the hybrid KEM", the
  "AAD/KDF context binding a scope-key wrap", "vector-pinned `sym_identity_pins`", the `K_name` wrap.
- **ADR 0017 §2** is literally the **hybrid KEM (X25519 + ML-KEM-768)** for key wrapping, and **rule 4** makes
  `contracts/crypto/vectors/v1.json` the single byte-definition source-of-truth (the "vector pin").
- The drop commit says so outright: *"the crypto conformance vectors moved from 0009 to ADR 0017."*

So the repoint is semantically exact, not a shrug that parks the reference on any live ADR.

## Scope — the 6 hand-written sites (the 20 generated ones are gitignored derivation)

| file | before | after |
|------|--------|-------|
| `contracts/api/scopes.openapi.yaml:12` | `the hybrid KEM, ADR 0009)` | `… ADR 0017)` |
| `web/src/features/spaces/grant.ts:9` | `(ADR 0009/0020)` | `(ADR 0017/0020)` |
| `web/src/features/spaces/scopes.ts:18` | `(ADR 0009/0020)` | `(ADR 0017/0020)` |
| `web/src/features/spaces/inviteName.ts:14` | `(ADR 0009/0027)` | `(ADR 0017/0027)` |
| `web/src/features/account/identityPins.ts:18` | `(ADR 0009, vector-pinned …)` | `(ADR 0017, …)` |
| `web/src/features/account/identityPins.ts:185` | `(ADR 0009,` | `(ADR 0017,` |

The paired feature ADRs (0020 grants, 0027 name-key) are untouched — only the `0009` token moves. The 20
generated refs under `web/src/api/generated/scopes/` are **gitignored** (derivation), so out of the diff; a
clean `npm run generate:api` reproduces them from the corrected spec with **0** dangling.

**`cr-0009` is NOT a target** — it is the tenant Slice-A change-request number, unrelated to the deleted ADR;
the sweep matches only the literal `ADR 0009`.

## Out of scope (stays in #95)

- **#95 (a)** — ADR 0017 rule 4 still claims *"the operator-blind backend verifies signatures against the same
  file"*, which no backend code does. A pre-existing inaccuracy in ADR 0017 itself, not introduced here; needs
  a content decision (correct the rule, or drop its backend clause).
- **The guard question** — nothing validates that a cited ADR still resolves. The 27 corpses are swept; the gap
  that let them linger is not closed.
- **#95 (b)** was already resolved before this CR: the *"native byte-identical"* comment in `inviteName.ts` had
  already been reworded away (verified: no such claim remains in `web/src`).

## Verification

- `npm run generate:api` → clean regen yields **0** `ADR 0009` in `web/src/api/generated/` (one file the
  incremental writer skipped on an unchanged content-hash cleared by `rm -rf`+regen).
- `git diff` confirms the change is **only** the ADR token — no semantics, no wire, no crypto.
- `npm run check` green (654 tests, build ✓); all 10 `validate_*.py` guards green.
- #95 reconciled: (b)+(c) marked done, (a)+guard left open; `docs/decisions/README.md` lesson updated.
