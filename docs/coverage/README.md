# Acceptance coverage

The machine-checked end of the trust chain: **feature → use-case → acceptance criteria → tests (or
explicit gaps)**. One coverage file per use-case, mirroring the feature tree:

```
docs/coverage/features/<feature>/use-cases/uc.<feature>.<goal>.yaml
```

Each file lists acceptance criteria; every criterion's tests are keyed by **area** (which surface) then
**level** (which kind of test). Surfaces that are intentionally not yet covered are declared under
`gaps` with a reason — never left silent.

`scripts/validate_coverage.py` enforces the chain (run it after changing a use-case, a coverage file,
or the tests they point to).

## Dimensions

**Area** — *where* the test runs (the surface): `backend` · `http` · `web` · `apple` · `android`.

**Level** — *what kind* of test it is. Classify by the **scope of behaviour under test**, not by the
test harness:

| Level | Scope under test |
| --- | --- |
| `unit` | a single component in isolation (even if it uses the DI container to obtain it) |
| `integration` | several components together across an internal boundary, in-process (e.g. HTTP resource → handler → DB) |
| `e2e` | the system through its external interface against a running instance (e.g. `.http` via ijhttp, or a browser) |

> Examples: a Quarkus `…SliceTest` (RestAssured → resource → handler → H2) is **integration**; a focused
> deserialization test is **unit**; `http/**/*.http` run by ijhttp is **e2e**.

## Shape

```yaml
useCase: uc.<feature>.<goal>
feature: <feature>
title: <title>
acceptanceCriteria:
  - id: <kebab-id>
    text: <one sentence>
    coverage:
      <area>:
        <level>: [<ref>, ...]   # backend ref: "ClassName.testMethod"; http ref: "http/<area>/<file>.http"
gaps:
  <area>: <reason>              # surfaces intentionally not (yet) covered
```

## Rules

- **Every acceptance criterion needs at least one test** (any area/level). This is enforced.
- **Level is descriptive, not enforced.** It records *what kind* of test covers a criterion so gaps are
  visible (e.g. "happy path has integration but no e2e yet") — the validator does **not** require any
  particular level, because coverage is risk-based, not box-ticking.
- **Areas and levels come from the fixed sets above**; unknown names fail validation (catches typos).
- **References must resolve:** a `backend` ref's test method must exist in `backend/src/test`; an `http`
  ref's file must exist. `web`/`apple`/`android` refs are not verified until those surfaces have code.
- **Gaps are explicit:** an uncovered surface is listed under `gaps` with a reason; silent omission is
  not allowed.
- **Stop at area × level.** Do not add finer per-test metadata (individual assertions, requirement ids) —
  classification noise without a decision payoff.
