---
id: cr-0033
title: Content-layer coverage catch-up + coverage is part of every CR
status: done
satisfies: [uc.memory.browse, uc.spaces.switch-spaces]
adrs: []
invariants: [operator-blind-e2ee]
---

## Goal

Close the **trust-chain gap** the content layer accumulated: the Memory-browse + space-switch use-cases had
shipped (cr-0026/0027/0030/0031) without coverage files, so `scripts/validate_coverage.py` was red. Map them to
their real tests, add the missing `http/` boundary acceptance, and make **coverage a required part of every CR**
going forward (so this can't recur).

## Context & constraints

- Durable truth: [uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md),
  [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md);
  [coverage README](../docs/coverage/README.md) (the area×level model + `validate_coverage.py`).
- The content endpoints' **happy/authz** path needs the operator-blind scope-grant flow (a seeded
  member+tenant+scope), reproduced in the **integration** slice tests (RestAssured → resource → handler → H2),
  not from a bare `.http`. The `http/` surface therefore covers the **unauthenticated boundary** (401), runnable
  against any instance — the established convention (cf. `http/admin/tenant.http`).

## Phases

- [x] 1 coverage YAMLs — `uc.memory.browse.yaml` + `uc.spaces.switch-spaces.yaml`, each acceptance criterion
  mapped to real backend (integration: NodesSliceTest / ScopesSliceTest / FavoritesSliceTest) + web (unit) tests;
  apple/android declared as explicit `gaps` (web-first shell). Done when: `python3 scripts/validate_coverage.py`
  green.
- [x] 2 http acceptance — `http/spaces/scopes.http` + `http/content/content.http` (the 401 boundary of the scope
  + tree + favorites endpoints) + per-dir env. Done when: both `ijhttp … RUN SUCCESSFUL` against a running
  instance.
- [x] 3 process — `changes/README` makes coverage a required CR rule + a template phase + part of the DoD
  (a CR is not done while the trust chain is red). Done when: documented.

## Definition of done

`python3 scripts/validate_coverage.py` green (10 use-cases mapped, all criteria covered); the referenced backend
slice tests green; the two new `http/` files run green via ijhttp; the CR template now requires coverage.
**Note:** favorites is an enabler (`en.memory.favorites`) — the catalog tracks use-cases, so `FavoritesSliceTest`
is exercised in `./gradlew check` but not catalogued separately (consistent with how enabler tests fold in).
