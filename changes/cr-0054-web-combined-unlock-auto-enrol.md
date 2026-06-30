---
id: cr-0054
title: Web combined login/unlock + auto-enrol — one "remember me" toggle, server-stored password-less wrap (web/backend)
status: done
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

- [x] 1 new wrap method (contracts + backend) — added the password-less `DEVICE_KEY` value to
  `MasterKeyWrap.Method` (+ `AddWrapRequest.method`) across the three identical-duplicate specs
  (account/setup/tenants); regenerated. No hardcoded Kotlin method enum needed changing — `forUser` filters only
  RECOVERY (so `DEVICE_KEY` is served) and `addWrap` takes `method` as an opaque string. One real backend change:
  `KeyMaterialStore.deleteWrap` whitelisted both device-local methods (`DEVICE`, `DEVICE_KEY`) so re-enrol can
  replace a prior `DEVICE_KEY` wrap. **Decision:** the AEAD iv rides `kdfParams` (opaque base64, no contract-shape
  change), the ciphertext rides `wrappedKey`. · Done: `./gradlew check` green; `AccountWrapsSliceTest` adds +
  reads back a `DEVICE_KEY` wrap (rides normal material, removable).
- [x] 2 web password-less wrap (web) — new `rememberMe/deviceKeyWrap.ts`: `enrolDeviceKey` (non-extractable
  AES-GCM key in IndexedDB → seal Master Key → `POST` the ciphertext as a `DEVICE_KEY` wrap, store key + wrapId +
  email locally, replace-prior + rollback-on-local-failure), `restoreDeviceKey(material)` (find the `DEVICE_KEY`
  wrap by id → decrypt with the local key), and `clearDeviceKey(deleteServerWrap)`. Retired cr-0052's
  `vaultCache.ts`; `unlock.ts` `restoreVaultFromCache`, `login.ts` `logout`, and `boot.ts` rewired. · Done:
  `npm run check` green; `deviceKeyWrap.test.ts` round-trips enrol → restore, replace, rollback, and the three
  fallbacks (no record / wrap gone / undecryptable).
- [x] 3 combined screen + auto-enrol (web) — `LoginView`: a single **"remember me"** toggle (removed the unlock
  "trust this device" checkbox + its orphaned i18n keys). On a floor unlock **auto-enrol** via
  `enrolConvenienceWrap()` — remember-off → DEVICE wrap, remember-on → `DEVICE_KEY` wrap + cookie; one wrap per
  device (§9), so the other method is dropped first. The Secret-Key floor shows only when device-unlock is
  unavailable; a returning DEVICE device upgrades to password-less only when the toggle is on (no needless
  re-enrol). · Done: `npm run check` green; the three states are covered across the module tests
  (login / unlock floor / unlock.device / deviceKeyWrap), the enrol-replaces rule in `deviceKeyWrap.test.ts`.
  (No component-test harness exists in web — the end-to-end screen adaptivity is a live-smoke item, per phase 4.)
- [x] 4 coverage + verify — flipped the web refs on
  [uc.account.remember-this-device.yaml](../docs/coverage/features/account/use-cases/uc.account.remember-this-device.yaml)
  (now implemented, web + backend refs per criterion) + the web part of
  [uc.account.auto-unlock.yaml](../docs/coverage/features/account/use-cases/uc.account.auto-unlock.yaml)
  (server-stored `DEVICE_KEY` wrap replaces the local cache). The three trust-chain guards green. **Live
  three-state browser smoke green** (re-seeded a fresh instance; signed in as the `127.0.0.1` tenant owner): (1)
  first sign-in → email+password → Secret-Key floor → unlock → landed in the shell with a `DEVICE` wrap enrolled
  (`veyq-trust` has the record, `veyq-remember` empty); (2) returning remember-off → email+password only →
  device-wrap unlock → straight to shell, the Secret-Key floor **never** shown; (3) remember-on first sign-in →
  unlock enrolled a `DEVICE_KEY` wrap (`veyq-remember` `current`, `veyq-trust` empty — one wrap per device held)
  + the httpOnly cookie, and a full **reload opened straight into the shell** (no login form, no floor during
  boot); (4) negative — cleared device (dropped the local key) → reload falls back to the sign-in screen, no
  silent open. Verified via accessibility snapshots + IndexedDB inspection + route/network assertions (the
  preferred text-based proof); screenshots couldn't be captured — a known machine-local preview gotcha pauses
  `requestAnimationFrame` in the backgrounded tab, so the renderer never paints (slivered images / hung rAF), an
  environment limit unrelated to the app.

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

**What shipped.** The web unlock model from ADR 0005 §17-21: one combined login/unlock screen with a single
"remember me" toggle that auto-enrols the matching convenience wrap on the first unlock per device — remember-off
→ a password `DEVICE` wrap (email+password next time), remember-on → a password-less `DEVICE_KEY` wrap + the
persistent cookie (opens straight in). cr-0052's two checkboxes + local IndexedDB Master-Key cache are gone.

**Design calls made during build (the CR left them open):**
- **iv placement.** The `DEVICE_KEY` wrap is `AES-GCM(deviceKey, masterKey)` from a non-extractable WebCrypto key,
  so it can't use `v1.symWrap` (no key bytes in JS). The ciphertext rides `wrappedKey`; the 12-byte iv rides
  `kdfParams` (opaque base64) — no contract-shape change, `AddWrapRequest.kdfParams` stays required.
- **"One wrap per device" (§9) coordination.** Rather than couple the two modules, `LoginView.enrolConvenienceWrap`
  drops the *other* method before enrolling the chosen one (remember-on → `untrustThisDevice` + `enrolDeviceKey`;
  off → `clearDeviceKey` + `trustThisDevice`); each enrol still replaces its own prior. A returning DEVICE device
  only *upgrades* to password-less when the toggle is on — no wasteful re-enrol of the same method each login.
- **Backend `deleteWrap` whitelist.** Was DEVICE-only; widened to `{DEVICE, DEVICE_KEY}` so re-enrol can replace a
  prior `DEVICE_KEY` wrap. The `forUser` RECOVERY-only filter already served `DEVICE_KEY`, and `addWrap` took
  `method` as an opaque string — so the contract enum was the only other backend change.
- **logout.** Drops the `DEVICE_KEY` server wrap + local key *before* revoking (no orphan ciphertext); leaves a
  `DEVICE` trust wrap intact (it survives sign-out by design — next login stays password-only).

**Testing shape.** No Vue component-test harness exists in web, so the three device states are covered at the
module level (login / unlock floor / unlock.device / deviceKeyWrap enrol+restore+replace+fallbacks), with the
end-to-end screen adaptivity proven by the live smoke. That matched the repo's prior CRs (gallery, shell).

**Friction / next time.**
- **Stale shared dev seed.** The first live-smoke attempt hit `verify 401`; the committed dev-seed no longer
  matched the shared backend (a parallel session had re-created the DB). Confirmed with the project's own seed
  harness before attributing it (cheap, decisive) rather than guessing at the code. Re-seeding needs `down -v` +
  a backend restart, which disrupts a parallel session — worth a heads-up that the local dev backend is shared.
- **preview rAF gotcha.** Screenshots are unobtainable in a backgrounded preview tab on this machine — `rAF` is
  paused, so the renderer never paints and rAF-based evals hang. Text-based verification (snapshots, IndexedDB,
  route/network) is both sufficient and the preferred path; don't burn turns chasing the screenshot.
- **`preview_click` vs `requestSubmit()`.** A `preview_click` on the submit button didn't fire the form handler;
  `form.requestSubmit()` via eval was reliable. Prefer it for PrimeVue forms.
