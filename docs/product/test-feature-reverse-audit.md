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

## Engineering-Trust Tests (not product acceptance criteria)

These frontend tests protect shared technical infrastructure — routing guards, theming/favicon,
deep-link/app-banner helpers, date and path utilities. They prove engineering trust rather than a
Memory-level product, operator, API or platform acceptance criterion, so they are listed here
(decision `engineering-trust`) instead of being mapped under a use-case coverage YAML.

### App-link / Smart App Banner helpers

The web shell's Safari Smart App Banner and `veyq://` deep-link plumbing — pure helper and component
behavior driven by runtime bootstrap metadata; no user-facing Memory acceptance criterion of its own.

- `frontend/src/components/app/AppLinkBanner.test.ts::uses runtime bootstrap metadata for the Safari Smart App Banner`: engineering-trust — app-banner component wiring.
- `frontend/src/components/app/AppLinkBanner.test.ts::does not leave Smart App Banner metadata when the instance has no app id`: engineering-trust — app-banner component wiring.
- `frontend/src/shared/utils/appLinks.test.ts::detects iOS browser user agents`: engineering-trust — deep-link/user-agent helper.
- `frontend/src/shared/utils/appLinks.test.ts::builds canonical http app urls from the current location`: engineering-trust — deep-link/user-agent helper.
- `frontend/src/shared/utils/appLinks.test.ts::rejects non-http locations`: engineering-trust — deep-link/user-agent helper.
- `frontend/src/shared/utils/appLinks.test.ts::encodes canonical links as veyq open urls`: engineering-trust — deep-link/user-agent helper.
- `frontend/src/shared/utils/appLinks.test.ts::creates Safari Smart App Banner content only when configured`: engineering-trust — deep-link/user-agent helper.
- `frontend/src/shared/utils/appLinks.test.ts::upserts and removes Safari Smart App Banner metadata`: engineering-trust — deep-link/user-agent helper.

### Router guard

The Vue router guard's setup/lock/public-route/invite-fragment redirects — navigation infrastructure
that every feature relies on; the per-feature product behavior is asserted in the feature use-cases.

- `frontend/src/router.test.ts::forces setup when the instance is not set up yet`: engineering-trust — router guard infrastructure.
- `frontend/src/router.test.ts::sends a locked visitor to login on a protected route`: engineering-trust — router guard infrastructure.
- `frontend/src/router.test.ts::keeps public routes reachable while locked`: engineering-trust — router guard infrastructure.
- `frontend/src/router.test.ts::routes an invite-fragment link to the accept view`: engineering-trust — router guard infrastructure.

### Theme / favicon state

Theme preference resolution and active-favicon swapping — cosmetic shell infrastructure with no
Memory-level acceptance criterion.

- `frontend/src/shared/state/useThemeState.test.ts::sets the light favicon when system preference resolves to light`: engineering-trust — theme/favicon infrastructure.
- `frontend/src/shared/state/useThemeState.test.ts::replaces the active favicon for an explicit dark theme`: engineering-trust — theme/favicon infrastructure.
- `frontend/src/shared/state/useThemeState.test.ts::replaces the active favicon when switching back to light`: engineering-trust — theme/favicon infrastructure.
- `frontend/src/shared/state/useThemeState.test.ts::replaces the active favicon when system preference changes`: engineering-trust — theme/favicon infrastructure.

### Date & path utilities

Low-level ISO-date round-tripping and memory-path/route/API-path conversion helpers — shared utility
code; behaviors that matter to users are exercised through the feature flows that consume them.

- `frontend/src/shared/utils/isoDate.test.ts::round-trips ISO dates through local Date instances without timezone shifts`: engineering-trust — date utility helper.
- `frontend/src/shared/utils/isoDate.test.ts::keeps empty or invalid values empty`: engineering-trust — date utility helper.
- `frontend/src/shared/utils/paths.test.ts::converts route path params to memory paths`: engineering-trust — path utility helper.
- `frontend/src/shared/utils/paths.test.ts::converts memory paths to frontend routes`: engineering-trust — path utility helper.
- `frontend/src/shared/utils/paths.test.ts::encodes api paths and path queries`: engineering-trust — path utility helper.
- `frontend/src/shared/utils/paths.test.ts::detects settings paths by the final segment`: engineering-trust — path utility helper.

## Pending Native Coverage Mapping

None. Native iOS unit-test mappings now live directly in Feature YAML under `ios.unit`.

If a future test is intentionally not mapped, record it in this exact form so the validator can allow it:

```text
- `path/to/TestFile.test.ts::test name`: reason for deletion candidate.
```

Delete candidates must be removed from this list when the test is deleted or mapped to a Feature YAML.
