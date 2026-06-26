# Cross-platform parity matrix

Per [developer styleguide §11](developer-styleguide.md), the surfaces **http / web / apple / android**
(plus **backend**) are kept at parity; where a surface intentionally differs, it is **marked
explicitly** here. This is the one-glance overview of *which use-case is implemented on which surface*.

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
| [uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md) — Trust this device (DEVICE wrap) | ✅ | ✅ | ✅ | ◻ | ◻ |

### Intentional N/A

- **uc.admin.setup-server — apple, android:** First-run server setup is done in the browser while the
  operator is at the machine installing the server — that is faster than a native app for a one-time
  bootstrap. The native clients only ever connect to an already-bootstrapped instance; they never
  bootstrap one. No native implementation is planned.
