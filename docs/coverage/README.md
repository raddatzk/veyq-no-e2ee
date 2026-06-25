# Acceptance coverage

The machine-checked end of the trust chain: **feature → use-case → acceptance criteria → tests (or
explicit gaps)**. One coverage file per use-case, mirroring the feature tree:

```
docs/coverage/features/<feature>/use-cases/uc.<feature>.<goal>.yaml
```

Each file lists acceptance criteria; every criterion links **at least one test** by surface
(`backend`, `http`, `web`, `apple`, `android`). Surfaces that are intentionally not yet covered are
declared under `gaps` with a reason — never left silent.

`scripts/validate_coverage.py` enforces this: every use-case has a coverage file, every criterion has a
test, and referenced backend test methods / `.http` files actually exist (dead references fail early).
Run it after changing a use-case, a coverage file, or the tests they point to.

## Shape

```yaml
useCase: uc.<feature>.<goal>
feature: <feature>
title: <title>
acceptanceCriteria:
  - id: <kebab-id>
    text: <one sentence>
    tests:
      - { area: backend, ref: "ClassName.testMethod" }
      - { area: http, ref: "http/<area>/<file>.http" }
gaps:
  - { area: web, reason: "deferred to a follow-up CR" }
```
