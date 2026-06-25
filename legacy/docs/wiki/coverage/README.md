# Greenfield Use-Case Coverage

This directory is the active acceptance trust chain for the Greenfield E2EE stack. It mirrors the
wiki feature/use-case tree:

```text
docs/wiki/features/<feature>/use-cases/uc.<feature>.<goal>.md
docs/wiki/coverage/features/<feature>/use-cases/uc.<feature>.<goal>.yaml
```

Each coverage YAML belongs to exactly one use-case. It links the use-case, its feature index and the
supporting artifacts that define the behavior: cross-cutting concepts, decisions, API contracts and
code entry points.

Acceptance criteria then map that use-case behavior to automated proof or explicit gaps. Coverage
areas are:

- `backend.unit` / `backend.integration`
- `web.unit` / `web.e2e`
- `android.unit` / `android.e2e`
- `apple.unit` / `apple.e2e`

Run:

```bash
node scripts/validate-feature-coverage.mjs
```
