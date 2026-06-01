# Test To Feature Reverse Audit

This audit tracks the reverse side of the feature trust chain:

```text
Existing test -> Feature / acceptance criterion -> Keep, move or delete decision
```

Feature YAML files are the source of truth for accepted product and platform behavior. This file now records only reverse-audit policy, current validation status and explicit delete candidates. The detailed mapping lives in the Feature YAML coverage entries.

## Current Snapshot

The current catalog covers:

- 26 feature YAML files.
- 520 current test cases across backend, frontend unit/component tests and Playwright specs.
- 0 unmapped current tests.
- 0 delete candidates.

Run the deterministic validator with:

```bash
node scripts/validate-feature-coverage.mjs
```

The validator fails when a current test is neither referenced from Feature YAML coverage nor listed below as a delete candidate.

## Mapping Policy

Use these decisions while resolving future unmapped tests:

- `map`: the test proves an existing acceptance criterion and should be added under that AC.
- `add-ac`: the test proves real behavior in an existing feature, but no current AC expresses it.
- `add-feature`: the test proves real behavior that needs its own feature YAML.
- `engineering-trust`: the test protects shared technical infrastructure; keep it in a `platform.*` or `frontend.*` Feature YAML with `capabilities: []`.
- `delete-candidate`: the test no longer proves product, operator, API or platform trust behavior.

No test should be deleted solely because it was temporarily unmapped. A deletion decision requires one of:

- the behavior is obsolete,
- the behavior contradicts the current Memory product direction,
- the test only checks an implementation detail with no product, operator, API or platform trust value,
- a better higher-level test already proves the same behavior and the lower-level test has become redundant.

## Delete Candidates

None.

## Pending Native Coverage Mapping

None. Native iOS unit-test mappings now live directly in Feature YAML under `ios.unit`.

If a future test is intentionally not mapped, record it in this exact form so the validator can allow it:

```text
- `path/to/TestFile.test.ts::test name`: reason for deletion candidate.
```

Delete candidates must be removed from this list when the test is deleted or mapped to a Feature YAML.
