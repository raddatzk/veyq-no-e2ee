# Cross-platform parity matrix

Per [developer styleguide §11](developer-styleguide.md), the surfaces **http / web / apple / android**
(plus **backend**) are kept at parity; where a surface intentionally differs, it is **marked
explicitly** here. This is the one-glance overview of *which use-case is implemented on which surface*.

The cross-platform **mechanism** behind the passwordless levels (L2/L3) — one design, native APIs per
surface — is documented in [Passwordless unlock mechanism](passwordless-unlock-mechanism.md).

This tracks **implementation status / intent** (is the behaviour built, or deliberately N/A on a
surface). It is **not** the test-coverage chain — that lives in
[`docs/coverage/`](../coverage/README.md), keyed per use-case under `gaps` — and **not** the *visual*
parity axis, which is [`ui-design-parity.md`](ui-design-parity.md) (one veyq design, native per surface).
A surface can be implemented here yet still have an open coverage gap, or vice versa; the axes are
independent and may legitimately disagree.

## Legend

- ✅ — implemented
- ◻ — planned / not yet implemented
- **N/A** — intentionally not applicable on this surface (reason given below the table)

## Matrix

| Use-case | backend | http | web | apple | android |
| --- | :---: | :---: | :---: | :---: | :---: |
| [uc.admin.setup-server](../features/admin/use-cases/uc.admin.setup-server.md) — First-run server setup | ✅ | ✅ | ✅ | N/A | N/A |
| [uc.account.login](../features/account/use-cases/uc.account.login.md) — Account login (password) | ✅ | ✅ | ✅ | ✅ | ✅ |
| [uc.account.unlock](../features/account/use-cases/uc.account.unlock.md) — Account unlock (PASSWORD wrap) | ✅ | ✅ | ✅ | ✅ | ✅ |
| [uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md) — Trust this device (DEVICE wrap, L1) | ✅ | ✅ | ✅ | ✅ | ✅ |
| [uc.account.set-device-security](../features/account/use-cases/uc.account.set-device-security.md) — Per-device unlock-level chooser | N/A | N/A | ◻ | ✅ | ✅ |
| [uc.account.biometric-unlock](../features/account/use-cases/uc.account.biometric-unlock.md) — Biometric unlock (L2, biometric-gated key) | N/A | N/A | **N/A** | ✅ | ✅ |
| [uc.account.auto-unlock](../features/account/use-cases/uc.account.auto-unlock.md) — Auto unlock (L3, no login) | N/A | N/A | ◻ | ✅ | ✅ |

### Intentional N/A

- **uc.admin.setup-server — apple, android:** First-run server setup is done in the browser while the
  operator is at the machine installing the server — that is faster than a native app for a one-time
  bootstrap. The native clients only ever connect to an already-bootstrapped instance; they never
  bootstrap one. No native implementation is planned.
- **uc.account.set-device-security / biometric-unlock / auto-unlock — backend, http:** these are
  **client-only** levels over the existing wraps endpoints (no new backend behaviour or wire contract),
  so the backend/http surfaces carry nothing distinct to test for them.
- **uc.account.biometric-unlock — web:** the web biometric path is a **WebAuthn passkey + PRF** (the
  separate, planned PASSKEY use-case), not this native Secure-Enclave/Keystore method; this use-case is
  native-only by design. (L2's `web` is N/A; L3/`set-device-security` on web remain ◻ — a web "stay
  signed in" level is conceivable later.)
