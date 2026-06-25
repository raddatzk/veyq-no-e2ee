---
id: cr-0005
title: Trust this device (web DEVICE wrap — unlock with only the password)
status: done             # planned → in-progress → done (then delete the file)
satisfies: [uc.account.trust-device]
adrs: [0005, 0003]
invariants: [operator-blind]
---

## Goal

Let a web user **trust the current device** so that, after the first password+Secret-Key unlock, routine
unlock on that device needs **only the password** — the Secret Key goes back in the Emergency Kit. Trust is a
**device-local DEVICE `MasterKeyWrap`**: `KEK = Argon2id(deviceFactor + password)` where `deviceFactor` comes
from a **non-extractable HMAC key in IndexedDB**, so the second factor is device-bound and exfiltration-
resistant (never raw bytes in JS), and the password stays a real factor. Adds two authed endpoints to
**add/remove** a wrap, extends the unlock flow with the DEVICE path (+ "unlock another way" → the PASSWORD
floor), and adds a "trust / untrust this device" control. **Multi-device** (a phone *and* a laptop each enrol
their own wrap). The **worker-cached re-lock / reload persistence** (ADR 0005 §6), **passkey/biometric**,
**cross-device onboarding**, and **native** are each separate, out of scope here.

## Context & constraints

- Use-case: [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md)
  (DEVICE wrap = device key + password; multi-device; untrust; floor always reachable).
- Concepts: [Account & Key Model](../docs/crosscutting/account-key-model.md) (one Master Key / many wraps),
  [Security Model](../docs/crosscutting/security-model.md) (2SKD, operator-blind).
- Decisions: [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md) (DEVICE backing =
  non-extractable WebCrypto key in IndexedDB; method precedence; re-lock — §6 deferred),
  [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) (auth ≠ unlock).
- **Reuse (don't reinvent):** the cr-0004 `SessionService.resolveUserId` guard, the `bearerToken(headers)`
  extractor, the `KeyMaterialHandler`/`KeyMaterialStore` slice, and the v1 wrap machinery (`symWrap`/
  `symUnwrap`, `encodeKdfParams`). The `GET /api/account/key-material` already returns DEVICE wraps.
- **Do NOT port** `legacy/frontend/.../account/rememberedLogin/` — it stores the Secret Key and the raw
  Master Key in `localStorage`/`sessionStorage`, the exact pattern [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md)
  **rejects**. Only the *"trust this device"* affordance is reused; the data layer is new.
- **Invariants at risk:**
  - **operator-blind** — the server stores only the DEVICE **ciphertext** wrap + opaque metadata; the KEK,
    the device key, and the Master Key never leave the device. The add/remove endpoints act only on the
    **caller's own** wraps (ownership from the access token, never an id in the request body).
  - **2SKD floor intact** — enrolment wraps the **already-unlocked** Master Key; the **Secret Key is never
    read or stored**. The DELETE path **refuses to remove a PASSWORD wrap**, so the floor can't be deleted.

## Design decisions (settled)

- **DEVICE-wrap construction (reuses the wrap machinery; new conformance-pinned KEK).**
  `deviceFactor = HMAC(deviceKey, "veyq/v1/device-bind")` computed via `crypto.subtle.sign` over a
  **non-extractable HMAC `CryptoKey`** (`generateKey({extractable: false})`) held in IndexedDB; then
  `KEK = Argon2id(concat(lp(deviceFactor), lp(utf8(password))), salt)` — same arg order as
  `kekPasswordSecret` (factor first, password second) — and `DEVICE wrap = symWrap(KEK, masterKey,
  "master-key")`. New `v1.kekDevicePassword(deviceFactor, password, salt, params)` + a conformance vector;
  the suite stays byte-identical to the vectors. The WebCrypto/IndexedDB device-key handling is **platform
  glue**, NOT in `v1.ts` (it never sees raw key bytes — `v1` only takes the already-derived `deviceFactor`).
- **Enrol at unlock time, not as a later action.** cr-0004 clears the password the moment unlock succeeds,
  so a separate post-unlock "trust this device" control would have to **re-prompt** for it. Instead the
  unlock card carries a **"Trust this device"** checkbox and enrolment runs **right after a successful
  unlock**, reusing the just-entered password — which is **known-correct** (it just opened the floor wrap)
  and not yet cleared. The enrol step reads the Master Key from the vault (a copy), wraps it, and clears the
  password as before.
- **Multi-device, server is source of truth; re-trust replaces.** Each device enrols its **own** wrap (own
  key, own random salt). Enrol **appends** a wrap (`POST`, server returns its `id`); the client stores
  `{deviceKey, salt, wrapId}` in IndexedDB. At unlock the client finds **its** DEVICE wrap in the
  `key-material` set by the stored **salt** (`kdfParams`, already in the response — no schema change) →
  **one** Argon2id. Untrust deletes that wrap by `id`. **Re-trusting an already-trusted device replaces**:
  if IndexedDB already holds a `wrapId`, enrol first `DELETE`s it (then `POST`s the new one), so a device
  never accumulates duplicate wraps.
- **Endpoints (scoped to DEVICE this CR).** `POST /api/account/wraps { method: DEVICE, wrappedKey, kdfParams
  } → 201 { id }`; `DELETE /api/account/wraps/{id} → 204` — **idempotent like logout**: deletes only the
  caller's own **non-PASSWORD** wrap; an unknown id, another account's wrap, or the floor is a silent no-op
  (always 204 → no cross-account enumeration, floor unremovable). The endpoint can generalise to
  PASSKEY/RECOVERY later. **Own tag `account-wraps`** so it generates a **separate `AccountWrapsApi`**
  interface/resource (one path per tag avoids the greedy class-level `@Path("/api/account")` that would
  otherwise force one fat resource and shadow `key-material`).
- **Unlock precedence (client-side, ADR 0005 §7).** If a device key + matching DEVICE wrap exist, the unlock
  card defaults to **password-only**; **"unlock another way"** always falls back to the PASSWORD floor
  (password + Secret Key). On a device with no key, only the floor is offered. **Build it additively** — a
  small list of "available methods on this device" (today: DEVICE, PASSWORD) with a chosen default, **not**
  a hardcoded 2-way `if`, so PASSKEY / RECOVERY slot in later without a rewrite.
- **Endpoint nouns.** `POST`/`DELETE /api/account/wraps` sit beside the existing `GET
  /api/account/key-material` (the unlock bundle = wraps + identity blob + suite). The slight noun split is
  intentional: per-wrap mutation vs. the unlock-material read are different concerns.
- **Out of scope:** caching the unlocked Master Key in a worker across reloads (ADR 0005 §6) — orthogonal,
  applies to every method, its own slice.

## Future interactions to keep in mind (not built here)

- **Change-password breaks DEVICE trust.** `KEK = Argon2id(deviceFactor + password)`, so a future password
  change makes every DEVICE wrap un-openable and the change-password client can't re-wrap them (no access to
  other devices' `deviceFactor`). Trusted devices fall back to the floor and re-trust on next use — inherent
  and ADR-0005-consistent; the change-password use-case must own re-offering "trust this device". Recorded in
  the use-case quality notes.

## Phases

- [x] **1 — contracts.** Added to `account.openapi.yaml` under a new `account-wraps` tag: `POST
  /api/account/wraps` (authed; `AddWrapRequest { method: DEVICE, wrappedKey, kdfParams }` → `201
  AddWrapResult { id }`; `400`/`401`) and `DELETE /api/account/wraps/{id}` (authed; **idempotent 204**;
  `401`). New `AddWrapRequest`/`AddWrapResult` schemas; reused `ErrorResponse`. Corrected the `MasterKeyWrap`
  `kdfParams` description (DEVICE carries it) in both account + setup specs to keep them identical. **Done:**
  both clients generate (web `AccountWrapsApi` + `AccountApi`; backend `AccountWrapsApi` interface, the
  existing `AccountResource` unchanged so `compileKotlin` stays green), web typecheck green.
- [x] **2 — backend.** Added `AccountWrapsResource` (implements the generated `AccountWrapsApi`, reuses
  `bearerToken`) → `WrapsHandler` (resolves the caller via `resolveUserId`, one `@Transactional`) →
  `KeyMaterialStore.addWrap`/`deleteWrap` (`deleteWrap` only removes the caller's own **non-PASSWORD** wrap;
  otherwise a no-op). No migration. 7 slice tests green: add a DEVICE wrap → it appears in `key-material` (+
  returns an id); a second DEVICE wrap coexists (multi-device); delete by id removes it; **another user's
  delete is a no-op (204), the wrap survives**; deleting the **PASSWORD** floor is a no-op; a non-DEVICE
  method → 400; unauthenticated `POST`/`DELETE` → 401. **Done:** `./gradlew check` green (and the cr-0004
  `AccountKeyMaterialSliceTest` still 7/7 — no cross-class wrap pollution; the per-test reset is scoped to
  the wraps-test users).
- [x] **3 — http.** Added `http/account/trust-device.http` (7 assertions, **ijhttp green** against a live
  instance): unauthenticated + bogus-bearer `POST` and `DELETE` → generic 401; and three boundary 400s on
  `POST` (missing `wrappedKey`, a non-DEVICE `method`, a non-base64 `wrappedKey`) — confirming validation
  runs **before** the in-handler auth check. **Parity gap (slice-only, in the header):** the authed add
  (201 + id) / untrust (204) round-trip needs a signed session no `.http` handler can produce (same limit as
  cr-0003/0004) — covered by the phase-2 slice + the phase-4 web round-trip.
- [x] **4 — web.** *Crypto:* added `v1.kekDevicePassword(deviceFactor, password, salt, params)` +
  `device_password_argon2id` conformance vector (suite green, 19 tests); added a worker `deviceKek` op so
  the (frequent) trusted-device unlock runs off the main thread too. *Device adapter*
  (`features/account/trustDevice/deviceKey.ts`): generate/store/read a **non-extractable** HMAC `CryptoKey`
  in IndexedDB keyed per account, `deviceFactor` via `subtle.sign`, persist `{cryptoKey, kdfParams, wrapId}`.
  *Orchestration* (`trustDevice.ts`): `trustThisDevice` (enrols at unlock time, **re-trust replaces** via a
  prior `DELETE`), `untrustThisDevice`, `isTrusted`. *Unlock* (`unlock.ts`): `unlockWithDevice` finds the
  device's own DEVICE wrap by the stored salt (`kdfParams`) and derives password-only; returns false (→
  floor) when the key/wrap is absent, throws generically on a wrong password. *UI* (`LoginView.vue`):
  device-mode card (password-only) ⇄ floor card (Secret Key + **"Trust this device" checkbox**), an
  **untrust** control when unlocked, and **"use my Secret Key instead"**; method selection is a mode, not a
  hardcoded `if`, so PASSKEY/RECOVERY are additive. 11 unit tests (device round-trip, wrong-password,
  not-trusted, orphan-clears, enrol/re-trust/untrust). **Done:** `npm run check` green (56 tests). Preview
  smoke on a fresh instance carried the **full lifecycle** end-to-end (real WebCrypto + IndexedDB, no
  console errors): setup → floor unlock with "trust this device" ticked (a non-extractable key + a DEVICE
  wrap landed, `deviceTrusted=true`) → sign out → re-login → **DEVICE mode, unlocked with only the
  password** → untrust (server wrap + IndexedDB key removed) → re-login → back to the Secret-Key floor.
- [x] **5 — verify + coverage.** Filled `docs/coverage/features/account/use-cases/uc.account.trust-device.yaml`
  (10 acceptance criteria → the web round-trip + device round-trip + KEK conformance + the backend wraps
  slice + http boundaries; `gaps` for apple/android, with a note that the authed add/untrust round-trip
  isn't http-expressible), and added the `uc.account.trust-device` row to
  [platform-parity.md](../docs/crosscutting/platform-parity.md) (backend/http/web ✅, apple/android ◻).
  **Done:** `python3 scripts/validate_coverage.py` green (4 use-cases mapped, all criteria covered).

## Definition of done

A web user who has unlocked once with password + Secret Key can **trust the device** and thereafter unlock
with **only the password** — across reloads and fresh logins — backed by a non-extractable IndexedDB device
key; can **untrust** (which removes only that device's wrap and falls back to the floor); and can trust
**multiple** devices independently. The server holds only ciphertext wraps and never the device key, KEK,
Master Key, or Secret Key; the PASSWORD floor can't be deleted and is always reachable via "unlock another
way". Coverage for `uc.account.trust-device` is green and the parity matrix reflects it (backend/http/web).
Worker-cached re-lock, passkey/biometric, cross-device onboarding and native trust remain separate, planned
work. After merge, keep this file as a record (per the cr convention).

## Review follow-ups (post-phase-5)

An intensive code + security review (7 parallel angles) confirmed the crypto core is sound (password is a
real second factor, the HMAC key is non-extractable, the KEK mirrors the floor's construction, wrong inputs
fail at the AEAD tag, nothing secret crosses the wire) and surfaced robustness/hygiene fixes, applied:
- **#1 (security):** `deleteWrap` now **whitelists DEVICE** instead of blacklisting only PASSWORD — the
  untrust endpoint can no longer strip a RECOVERY/PASSKEY method (and doesn't hinge on the floor's casing).
  Contract + use-case `Untrust` updated; slice test for a RECOVERY delete being a no-op.
- **#2 (robustness):** `unlockWithDevice` no longer **destroys the local device key** when a wrap is
  missing — a blind operator could otherwise withhold it to force every trusted device to wipe its key. It
  just falls back to the floor; explicit untrust / re-trust cleans up. Use-case E1 updated.
- **#3 (UX/robustness):** enrol failure is no longer silently swallowed — a `saveTrust` failure **rolls
  back** the just-added server wrap (no orphan), and the UI shows a "couldn't trust this device" warning.
- **#5 (robustness):** the IndexedDB adapter rejects on `onblocked` (no hang) and settles writes on
  `transaction.oncomplete` (a commit-time abort no longer reports a false success).
- **#7 (hygiene):** `signOut` resets `deviceTrusted`/`trustFailed`; the device success path clears
  `secretKey` too; `submit()` guards the `isTrusted` IndexedDB call; `argon.worker` dispatches the op
  explicitly (no fallthrough). Re-verified by a live preview smoke (enrol → re-login DEVICE unlock) after
  the IndexedDB-timing change.

Logged, not actioned (out of this batch): cap on DEVICE wraps per account (#4), matching the wrap by
`wrapId` instead of `kdfParams` — needs the GET to expose the id (#6), folding `WrapsHandler` into
`KeyMaterialHandler` (#8). By design (ADR 0005, not a defect): a stolen *unlocked* device / in-session XSS
reduces to password strength — "wraps protect against key theft, not session misuse".
