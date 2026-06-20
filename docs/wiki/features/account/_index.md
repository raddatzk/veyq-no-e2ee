# Feature: Account & Auth

Accounts and authentication — passwords + passkeys, optional TOTP, invitations, profile, key
recovery and account erasure.

- **Touched contexts:** Identity & Access — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Platform Concepts → Auth & sessions](../../crosscutting/platform-concepts.md),
  [Security Model](../../crosscutting/security-model.md) (key hierarchy, recovery, onboarding),
  [Account Keys & Recovery (illustrated)](../../crosscutting/account-key-model.md).
- **Decisions:** [AD-06](../../decisions/README.md) (key recovery), [AD-08](../../decisions/README.md) (Secret Key), [AD-21](../../decisions/README.md) (onboarding).

## Use-cases
- ★ [Register via an invitation](use-cases/uc.account.register-via-invitation.md) — accept an invitation and create a cryptographic account
- ★ [Log in and unlock](use-cases/uc.account.login.md) — authenticate + unlock key material
- ★ [Enable TOTP](use-cases/uc.account.enable-totp.md) — second factor for password login
- ★ [Manage profile & credentials](use-cases/uc.account.manage-profile.md) — profile fields, passkeys/password, devices
- ★ [Change account email](use-cases/uc.account.change-email.md) — verified, token-gated email swap
- ★ [Recover key access](use-cases/uc.account.recover-keys.md) — recovery code / Emergency Kit / another device
- ★ [Erase account](use-cases/uc.account.erase-account.md) — self-service deletion (crypto-erasure)
