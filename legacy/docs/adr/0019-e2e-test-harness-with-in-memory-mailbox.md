# ADR 0019: E2E Test Harness with In-Memory Mailbox

## Status

Accepted

## Context

veyq needs real end-to-end coverage for browser and native mobile clients without weakening the production authentication model.

User onboarding intentionally depends on email-token flows for invitations, password resets and email changes. Browser E2E tests should exercise these flows through real product APIs and UI paths instead of bypassing them with direct token injection. At the same time, tests must remain deterministic, self-contained and runnable in local development and CI without external SMTP infrastructure.

Native mobile clients do not yet expose the full initial setup and tenant administration flow. Until those flows exist natively, mobile E2E tests need a controlled way to prepare a configured server, tenant and tenant membership before exercising mobile-specific behavior.

The existing feature coverage model distinguishes mocked frontend integration tests from real full-stack E2E coverage levels such as `e2e.browser` and `e2e.ios`.

## Decision

veyq will provide an explicit E2E test harness that is only available in the test profile.

The test harness will include an in-memory mailbox. In the E2E profile, application email delivery writes structured messages to this mailbox instead of sending mail through external SMTP. Tests may query and clear the mailbox through internal test-only APIs.

Browser E2E tests will use real product flows for setup, login, invitation acceptance, password reset and email confirmation. When a flow sends email, the test will read the corresponding message from the in-memory mailbox and continue with the real token link or token value from that message.

The test harness must not expose direct production-auth bypass behavior such as returning arbitrary access tokens from normal auth endpoints.

For mobile E2E tests, the harness may expose a setup fixture API that prepares the minimum required server state while mobile does not yet support that setup path natively. This fixture may create:

- an initialized server owner,
- a tenant,
- tenant memberships,
- baseline Memories or shares needed by the mobile test.

Mobile tests should still obtain native sessions through the real mobile session APIs once the server state exists.

All test-harness endpoints and mailbox access must be unavailable outside the E2E or test profile. Application startup should fail if the harness is enabled in an unsafe runtime profile.

E2E tests that prove product behavior must be linked from feature acceptance criteria using the appropriate coverage level, such as `e2e.browser` or `e2e.ios`.

## Consequences

Browser E2E tests can verify email-token flows without external SMTP services and without introducing hidden production behavior.

Test setup remains deterministic because tests can query the in-memory mailbox by run id, recipient and message type.

The in-memory mailbox is less production-like than a real SMTP server, so it does not prove SMTP configuration or deliverability. Operator-level SMTP behavior remains covered separately by configuration validation, smoke checks or future operational tests.

Mobile E2E can start before native setup and tenant administration are complete, but this creates a temporary fixture dependency. Once mobile supports the relevant setup and administration flows, those mobile tests should move toward product-level setup paths where useful.

The test harness becomes part of the developer-facing architecture and must be documented in development docs. The production runtime must explicitly guard against accidental exposure.
