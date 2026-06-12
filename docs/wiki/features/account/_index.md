# Feature: Account & Auth

Accounts and authentication — passwords + passkeys, optional TOTP, invitations, profile, key
recovery and account erasure.

- **Touched contexts:** Identity & Access — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Platform Concepts → Auth & sessions](../../crosscutting/platform-concepts.md),
  [Security Model](../../crosscutting/security-model.md) (key hierarchy, recovery, onboarding).
- **Decisions:** [AD-06](../../decisions/README.md) (key recovery), [AD-08](../../decisions/README.md) (Secret Key), [AD-21](../../decisions/README.md) (onboarding).

## Use-cases
- ☐ `uc.account.register-via-invitation` — accept an invitation and create an account
- ☐ `uc.account.login` — log in (password / passkey)
- ☐ `uc.account.enable-totp` — enable TOTP
- ☐ `uc.account.manage-profile` — manage profile
- ☐ `uc.account.recover-keys` — recover access via recovery code, Emergency Kit, or second device
- ☐ `uc.account.erase-account` — delete one's own account
