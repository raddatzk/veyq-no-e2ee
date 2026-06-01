# ADR 0018: TOTP for Password Login

## Status

Accepted

## Context

veyq supports local password accounts and passkeys. TOTP should strengthen password-based login without making passkeys worse from a user-experience or security perspective.

Passkeys are phishing-resistant authenticators and already provide a stronger login method than passwords with a shared-secret OTP. Stacking TOTP on every passkey login would add friction without matching the security model of passkeys.

## Decision

Add TOTP as an optional second factor for password-based login.

- A logged-in user can create a TOTP enrollment secret and activate it by confirming a current TOTP code.
- When TOTP is enabled, `/api/auth/login` still verifies email and password first, then requires `totpCode`.
- Passkey login remains a separate strong login path and does not require TOTP.
- Disabling TOTP requires the current password and a current TOTP code.
- Recovery codes, ServerOwner reset/disable authority, tenant/server enforcement policy and mobile handoff behavior are deferred follow-up decisions.

## Consequences

Password-only users can add a second factor without changing their account model. Passkey users are not forced through an additional TOTP challenge.

The first implementation stores a server-side TOTP secret because standards-compliant TOTP verification requires access to the shared secret. Operational hardening for secret encryption at rest can be added later without changing the public login contract.
