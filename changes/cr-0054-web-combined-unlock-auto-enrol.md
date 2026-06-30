---
id: cr-0054
title: Web combined login/unlock + auto-enrol — one "remember me" toggle, server-stored password-less wrap (web/backend)
status: planned
satisfies: [uc.account.remember-this-device, uc.account.auto-unlock]
adrs: ["0003", "0004", "0005"]
invariants: [operator-blind-e2ee]
---

## Goal

Realize the refined web unlock model ([ADR 0005 §17-21](../docs/decisions/0005-unlock-methods-and-device-trust.md),
[uc.account.remember-this-device](../docs/features/account/use-cases/uc.account.remember-this-device.md)): **one
adaptive login/unlock screen** with a **single "remember me" toggle** that, on the first unlock per device,
**auto-enrols** the matching convenience wrap — so the Secret Key is typed once per device and there is exactly
one per-device choice instead of cr-0052's two overlapping checkboxes.

- **remember off** → auto-enrol a **DEVICE** wrap (`Argon2id(deviceFactor + password)`, the existing method) →
  next launch: **email + password only**, no Secret Key.
- **remember on** → auto-enrol a **password-less device-key wrap** (a new `MasterKeyWrap.Method`) + the
  persisted session cookie → next launch: **opens straight in**.

**Supersedes parts of cr-0052:** the local IndexedDB Master-Key cache becomes the **server-stored** password-less
wrap (device key held locally, ciphertext on the blind server — portable + server-revocable, per
uc.account.auto-unlock); the two checkboxes (login "remember me" + unlock "trust this device") become **one**.

**Out of scope:** native (keeps its chooser, [uc.account.set-device-security](../docs/features/account/use-cases/uc.account.set-device-security.md) —
its richer biometric option + whether it also collapses to a toggle is ADR 0005 §21 "to be worked out"); passkey
auth; QR onboarding; the email-gated RECOVERY release.

## Context & constraints

- **The combined screen is mostly the existing `LoginView` flow.** `submit()` already does login → `isTrusted`
  + `unlockWithDevice` (password-only) → else the Secret-Key floor. The change is: **auto-enrol** on a floor
  unlock (not the opt-in "trust this device" checkbox), the **password-less** path, and **one** toggle. The
  "adaptive" fields fall out: the floor (Secret Key) only shows when device-unlock isn't available.
- **The password-less wrap = cr-0052's vault-cache crypto, but server-stored.** A non-extractable WebCrypto AES
  key in IndexedDB encrypts the Master Key; the **ciphertext** is uploaded as the new-method wrap (with its iv),
  not parked in IndexedDB. Boot: fetch key material → find the wrap → decrypt with the local key → Master Key
  (no password). Reuse `rememberMe/vaultCache.ts`'s key handling; move the ciphertext to the server.
- **New `MasterKeyWrap.Method`** (e.g. `DEVICE_KEY`): the contract enum + the web `MasterKeyWrapMethodEnum`. The
  server stores it as opaque ciphertext like any method; `KeyMaterialHandler.forUser` already serves everything
  except RECOVERY, so it is served (cr-0052). The iv rides `kdfParams` (or the wrap blob) — decide in build.
- **Auto-enrol, not opt-in (ADR 0005 §18-19).** A floor unlock always enrols a convenience wrap for *this*
  device; "remember me" only decides **which** (password DEVICE vs password-less). No "remember nothing" level —
  a stolen device + the password DEVICE wrap is useless without the password, and incognito covers public
  machines. Re-enrol **replaces** (one convenience wrap per device, §9): enrolling password-less drops the
  device wrap and vice-versa.
- **Operator-blind unchanged (ADR 0005 §12).** Server sees only ciphertext wraps + an opaque cookie; no KEK /
  Master Key / Secret Key / password. The new method is just another opaque blob + label.
- **Verify:** `cd web && npm run check` + `./gradlew -p backend check` green; the crypto conformance test + the
  three trust-chain guards green; a live browser smoke across the **three device states** — first sign-in
  (password+Secret Key → enrol), returning with remember-off (email+password only, no Secret Key), returning
  with remember-on (opens straight in) — plus the negative (incognito / cleared device → first-sign-in screen).

## Phases

- [ ] 1 new wrap method (contracts + backend) — add the password-less `DEVICE_KEY` value to `MasterKeyWrap.Method`
  in the contract; regenerate. The wraps `POST` + key-material `GET` already handle arbitrary methods (ciphertext
  + cap), so the only backend change is the enum. · Done when: `./gradlew -p backend check` green; a slice test
  adds + reads back a `DEVICE_KEY` wrap and confirms it rides the normal key material (not filtered like RECOVERY).
- [ ] 2 web password-less wrap (web) — `rememberMe/` module: enrol (non-extractable AES key in IndexedDB →
  encrypt Master Key → `POST` the ciphertext as a `DEVICE_KEY` wrap, store key + wrapId locally) and
  boot-restore (fetch key material → find the `DEVICE_KEY` wrap → decrypt with the local key → `openVault`).
  Retire cr-0052's local Master-Key cache (`vaultCache` becomes the device-key holder + server-wrap client). ·
  Done when: `npm run check` green; a test round-trips enrol → boot-restore → fallback-on-missing-key/wrap.
- [ ] 3 combined screen + auto-enrol (web) — `LoginView`: a single **"remember me"** toggle (remove the unlock
  "trust this device" checkbox); on a floor unlock **auto-enrol** — remember-off → DEVICE wrap, remember-on →
  `DEVICE_KEY` wrap + cookie; the Secret-Key field shows only when device-unlock is unavailable; re-enrol
  replaces the prior convenience wrap. · Done when: `npm run check` green; tests cover the three device states +
  the enrol-replaces-prior-wrap rule.
- [ ] 4 coverage + verify — flip the web refs on
  [uc.account.remember-this-device.yaml](../docs/coverage/features/account/use-cases/uc.account.remember-this-device.yaml)
  + the web part of [uc.account.auto-unlock.yaml](../docs/coverage/features/account/use-cases/uc.account.auto-unlock.yaml);
  live browser smoke across the three states + negative. · Done when: the three trust-chain guards green; the
  smoke confirmed with screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Combined login/unlock + auto-enrol + one toggle | Status |
|---|---|---|
| backend / contracts | new `DEVICE_KEY` wrap method (opaque) | ◻ this CR |
| web | one screen, one "remember me", auto-enrol, server-stored password-less wrap | ◻ this CR |
| apple / android | combined screen is shared (§17), but the **chooser** (incl. biometric) stays uc.account.set-device-security | ◻ **separate (native)** |

## Definition of done

A web device runs the refined flow: first sign-in takes password + Secret Key once and auto-enrols a convenience
wrap; thereafter "remember me" off → email + password only, on → opens straight in. There is **one** toggle (the
two checkboxes are gone), the remember-me vault is a **server-stored** password-less wrap (not a local blob), and
operator-blindness is untouched. `npm run check` + `./gradlew -p backend check` + the conformance test + the
three guards green; the three-state smoke confirmed with screenshots. cr-0052's local cache + two checkboxes are
superseded. Native, passkey, QR, and the RECOVERY release stay their own work.

## Retrospective

_(to be written at close)_
