---
id: cr-0131
title: Web ESLint baseline + dead-code guardrail (audit P1a)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Give the `web/` surface a real lint guardrail. Before this CR the project had **no ESLint** at all: the
five `<!-- eslint-disable-next-line vue/no-v-html -->` comments were inert (nothing enforced `vue/no-v-html`,
even though the client renders decrypted HTML and holds keys), and dead imports/refs were unguarded. This
adds an ESLint 10 flat config (typescript-eslint + eslint-plugin-vue), wires it into `npm run check`, and
fixes the fallout so the baseline lands **green**.

## Context & constraints

- **Scope = baseline, not a strictness sweep.** eslint-plugin-vue's `flat/recommended` also enforces ~1379
  template-formatting opinions (attribute-per-line, singleline-content newlines). Those are out of scope for a
  guardrail, so the Vue preset is **`flat/essential`** (error-prevention only) + typescript-eslint
  `recommended` (non-type-checked — fast, no `parserOptions.project` needed). `vue/no-v-html` is then bumped
  to **error** explicitly: it is the security backstop for the operator-blind model (server serves only
  ciphertext; the client renders decrypted content), so every `v-html` must stay an explicit, reviewed
  opt-out with a sanitising helper — never silent.
- **The audit named tsconfig `noUnusedLocals`/`noUnusedParameters`; those flags are the wrong tool here and
  were deliberately not used.** They are unscopable and the generated API client (`src/api/generated/**` —
  gitignored, regenerated from `contracts/`, never hand-edited) trips them ~50× (the `typescript-fetch`
  template emits inherently-unused params like `ignoreDiscriminator` and imports like `mapValues`).
  `exclude`-ing the dir does not help — TS still checks those files when they are imported. Instead the
  *intent* ("dead imports/refs unguarded") is carried by **`@typescript-eslint/no-unused-vars`** tightened to
  `args: 'all'` (matches `noUnusedParameters`) with the `^_` escape hatch (matches tsc's convention). Unlike
  the compiler flags, ESLint honours the ignore list, so generated code is correctly exempt.
- **`no-explicit-any`: error for production, off for test/tooling.** All 36 pre-existing `any`s live in test
  files, the documented gallery test-kit (`galleryFakeStore.ts`), dev seeders (`scripts/*.mts`) and
  `vite.config.ts` — **zero in production `src` runtime code**. So the rule stays an error everywhere and is
  relaxed only for that test/tooling override block: production stays `any`-clean and the guard keeps it that
  way, without churning test fixtures.
- **Fallout fixed, not suppressed** (6 real findings from `flat/essential` + no-unused-vars):
  - `favoritesStore.ts` (×2) + `MemoryBuckets.vue` — Set-toggle written as a ternary-for-side-effect
    (`s.has(x) ? s.delete(x) : s.add(x)`) → `if/else` (`no-unused-expressions`).
  - `MemoryDateEditor.vue` — a `computed` whose `switch` over `MemoryDateMode` had no `default`, so a
    non-matching value returned `undefined` (`vue/return-in-computed-property`) → added `default: return false`.
  - `share.test.ts` — dead `const minted =` (kept the `await createShare(...)` for its mock side effect).
  - `SettingsHubView.vue` — dead `allItems` computed removed.
- **Verify:** `cd web && npm run check` (now `lint → typecheck → test → build`).

## Phases

- [x] 1 web — ESLint 10 flat config (`eslint.config.js`: `flat/essential` + ts `recommended`,
  `vue/no-v-html` error, hardened `no-unused-vars`, `no-explicit-any` error/test-exempt), `lint` script,
  `lint` folded into `check`; 6 fallout fixes. · **Done:** `npm run check` green — ESLint 0 problems,
  vue-tsc clean, 566 tests pass (4 skipped), build ok.

## Definition of done

`cd web && npm run check` green with `npm run lint` as its first step; ESLint reports 0 problems; the five
`vue/no-v-html` opt-outs are now genuinely enforced; the trust-chain guards
(`validate_changes.py` / `validate_doc_links.py` / `validate_coverage.py`) stay green. No `satisfies:`, so no
coverage delta.

## Retrospective

- **Planning miss — took the finding's *prescription* (tsconfig `noUnusedLocals`/`noUnusedParameters`)
  literally before checking it fit.** Both flags collide head-on with the generated client and can't be
  scoped away; I only discovered this after enabling them and watching vue-tsc light up ~50 generated-file
  errors. Root cause: I treated an inbox finding's suggested mechanism as a spec instead of verifying it
  against the repo shape first (exactly the "verify checkable claims, don't inherit them" rule in the CR
  README). The fix — ESLint `no-unused-vars` scoped to non-generated code — is strictly better and is what the
  finding actually *wanted*; I should have reached it by design, not by fallout.
- **Execution miss — ran `npm install` from the repo root**, creating a stray root `package.json` /
  `package-lock.json` / `node_modules`; the lint "worked" only because Node resolves `node_modules` upward. A
  fresh `npm ci` in `web/` (i.e. CI) would have failed to find eslint. Caught it via `git status` before
  committing and reinstalled correctly inside `web/`. Root cause: the Bash tool's working directory silently
  reset to the repo root between calls and I didn't pin it. **Next time:** prefix every `web/` command with an
  explicit `cd web` (absolute path) rather than trusting persisted cwd — and sanity-check `git status` for
  stray root artifacts after any `npm install`.
- **Good:** kept the guardrail a genuine baseline (essential-only, no 1379-warning formatting sweep) and fixed
  all fallout rather than blanket-disabling — the six findings were real (a non-exhaustive computed, two dead
  bindings, three ambiguous side-effect ternaries).
