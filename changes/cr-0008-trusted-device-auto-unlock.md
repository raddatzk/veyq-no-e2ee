---
id: cr-0008
title: Trusted device unlocks at login (drop the redundant second "unlock" step)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.trust-device, uc.account.unlock]
adrs: ["0005", "0003"]
invariants: [operator-blind]
---

## Goal

On a **trusted device**, fold the DEVICE-wrap unlock **into login**: a successful sign-in unlocks the vault
**immediately**, with no separate "Erinnerungen entsperren / Entsperren" card. The Secret Key (here the
device factor) belongs to the very login that just happened and the password is still in hand, so prompting
again is pure friction — the extra click added **zero** security (anyone who could click "Entsperren" had
already logged in with the password).

## Context & constraints

- Use-cases: [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md)
  (§"Routine unlock on a trusted device") + [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md).
- **ADR-0005 method precedence preserved:** a trusted device defaults to password-only, and the **PASSWORD
  floor stays always reachable** — if the device key or its server wrap is unusable (E1), the client falls
  back to the Secret-Key floor **automatically**. The manual "use my Secret Key instead" button is no longer
  needed: there is no intermediate stuck state to escape from (success → unlocked; failure → floor).
- **ADR-0003 (auth ≠ unlock) intact:** the server still does not imply unlock — login gates the session,
  then the client opens the Master Key from the DEVICE wrap locally. Both still happen; only the extra UI
  click is removed.
- **Operator-blind:** unchanged — the DEVICE unlock is fully client-side; nothing new leaves the device.

## Phases

- [x] **1 — web.** `LoginView.submit()`: after a successful login, if the device `isTrusted`, run
  `unlockWithDevice(email, password)` right away; on success clear password/Secret Key and land on the
  unlocked card; on failure (E1) drop `deviceTrusted` and fall through to the Secret-Key floor. **`loggedIn`
  flips only once the outcome is known** — the busy sign-in card stays up during the trusted-device unlock so
  it transitions *straight* to "unlocked" and never flashes the Secret-Key floor as an intermediate step (the
  earlier version flipped `loggedIn` before awaiting the unlock, which briefly rendered the floor). Removed the
  now-unreachable **device-mode card**, the `unlockMode` ref, `useSecretKeyInstead()`, and the unused i18n
  keys `unlock.deviceNote` / `unlock.anotherWay` / `unlock.deviceUnavailable` (en+de). `unlock()` is now the
  floor path only. **Done:** `npm run check` green (58); HMR clean (no console errors). The crypto path
  (`unlockWithDevice`) is unit-covered; the floor (untrusted) card is unchanged.
- [x] **2 — docs.** Updated `uc.account.trust-device.md` §"Routine unlock on a trusted device": the web
  client takes the password from the login just performed, so the unlock **folds into login** (no separate
  step) and the floor is reached by **automatic** fallback on E1. **Done.**

## Definition of done

On a trusted device, signing in unlocks the vault in one step (no second "Entsperren" prompt). A device whose
key/wrap is gone falls back automatically to the password + Secret-Key floor; an untrusted device is
unchanged (floor card, with the "trust this device" enrol option). Operator-blindness and ADR-0005 precedence
hold. Keep this file as a record.
