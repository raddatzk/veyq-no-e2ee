# Feature: Account & Auth

Accounts and authentication - passwords, invitations, profile metadata, avatar handling, login
sessions, key recovery and account deletion.

- **Touched contexts:** Identity & Access — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Platform Concepts → Auth & sessions](../../crosscutting/platform-concepts.md),
  [Security Model](../../crosscutting/security-model.md) (key hierarchy, recovery, onboarding),
  [Account Keys & Recovery (illustrated)](../../crosscutting/account-key-model.md).
- **Decisions:** [AD-06](../../decisions/README.md) (key recovery), [AD-08](../../decisions/README.md) (Secret Key), [AD-21](../../decisions/README.md) (onboarding).

## Use-cases
- ★ [Register via an invitation](use-cases/uc.account.register-via-invitation.md) — accept an invitation and create a cryptographic account
- ★ [Log in and unlock](use-cases/uc.account.login.md) — authenticate + unlock key material
- ★ [Update profile](use-cases/uc.account.update-profile.md) — server-visible profile fields
- ★ [Set avatar](use-cases/uc.account.set-avatar.md) — direct-to-object-store profile image upload
- ★ [Delete avatar](use-cases/uc.account.delete-avatar.md) — remove the profile image
- ★ [Change password](use-cases/uc.account.change-password.md) — replace the PASSWORD master-key wrap
- ★ [Reset password](use-cases/uc.account.reset-password.md) — email-token + Secret-Key recovery wrap flow
- ★ [Manage login sessions](use-cases/uc.account.manage-login-sessions.md) — list, rename and revoke login sessions
- ★ [Change account email](use-cases/uc.account.change-email.md) — verified, token-gated email swap
- ★ [Recover key access](use-cases/uc.account.recover-keys.md) — recovery code / Emergency Kit / another device
- ★ [Delete account](use-cases/uc.account.delete-account.md) — self-service deletion (crypto-erasure)
