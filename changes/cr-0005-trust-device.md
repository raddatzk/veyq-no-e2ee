---
id: cr-0005
title: Trust this device (web DEVICE wrap — unlock with only the password)
status: planned          # planned → in-progress → done (then delete the file)
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
- **Multi-device, server is source of truth.** Each device enrols its **own** wrap (own key, own random
  salt). Enrol **appends** a wrap (`POST`, server returns its `id`); the client stores `{deviceKey, salt,
  wrapId}` in IndexedDB. At unlock the client finds **its** DEVICE wrap in the `key-material` set by the
  stored **salt** (`kdfParams`, already in the response — no schema change) → **one** Argon2id. Untrust
  deletes that wrap by `id`.
- **Endpoints (scoped to DEVICE this CR).** `POST /api/account/wraps { method: DEVICE, wrappedKey, kdfParams
  } → 201 { id }`; `DELETE /api/account/wraps/{id} → 204` (ownership-checked; **404** for someone else's or
  unknown id; **refuses** a PASSWORD wrap). The endpoint can generalise to PASSKEY/RECOVERY later.
- **Unlock precedence (client-side, ADR 0005 §7).** If a device key + matching DEVICE wrap exist, the unlock
  card defaults to **password-only**; **"unlock another way"** always falls back to the PASSWORD floor
  (password + Secret Key). On a device with no key, only the floor is offered.
- **Out of scope:** caching the unlocked Master Key in a worker across reloads (ADR 0005 §6) — orthogonal,
  applies to every method, its own slice.

## Phases

- [ ] **1 — contracts.** Add to `account.openapi.yaml`: `POST /api/account/wraps` (authed; body `{ method,
  wrappedKey, kdfParams }`, `method` restricted to `DEVICE`; → `201 { id }`; `400`/`401`) and
  `DELETE /api/account/wraps/{id}` (authed; `204`; `401`; `404` for unknown/other-owner; refuses PASSWORD).
  Reuse the `MasterKeyWrap`/`ErrorResponse` shapes. **Done when:** the spec lints and the backend/web
  clients generate.
- [ ] **2 — backend.** Extend the `account/keymaterial` slice (reusing `resolveUserId` + `bearerToken`):
  `KeyMaterialStore.addWrap(userId, method, wrappedKey, kdfParams) → id` (append a new row) and
  `deleteWrap(userId, id)` (delete only if the row is the caller's **and** not PASSWORD); a thin
  `AccountWrapsResource` (`POST`/`DELETE`). No migration (`MasterKeyWrapEntity` already supports DEVICE +
  has a UUID id). Slice tests: add a DEVICE wrap → it appears in `key-material` (+ returns an id); a second
  DEVICE wrap coexists (multi-device); delete by id removes it; **another user's token can't delete this
  user's wrap (404)**; deleting a **PASSWORD** wrap is refused; unauthenticated `POST`/`DELETE` → 401.
  **Done when:** `./gradlew check` green.
- [ ] **3 — http.** Add `http/account/trust-device.http`: unauthenticated/bogus-bearer `POST` and `DELETE`
  → generic 401; a malformed `POST` body → 400. **Parity gap (slice-only):** the authed add/delete
  round-trip needs a signed session no `.http` handler can produce (same limit as cr-0003/0004) — covered
  by the phase-2 slice. **Done when:** ijhttp green against a live instance.
- [ ] **4 — web.** *Crypto:* add `v1.kekDevicePassword(deviceFactor, password, salt, params)` + its
  conformance vector (suite stays green). *Device adapter* (`features/account/trustDevice/deviceKey.ts`, the
  platform glue): generate/store/read a **non-extractable** HMAC `CryptoKey` in IndexedDB keyed per account,
  compute `deviceFactor` via `subtle.sign`, and persist/read `{salt, wrapId}`. *Enrol* `trustThisDevice(
  password)` (only from the unlocked vault): generate the key, derive the KEK, `symWrap` the in-memory
  Master Key, `POST` it, store `{deviceKey, salt, wrapId}`. *Untrust:* `DELETE` by id + clear IndexedDB.
  *Unlock:* extend `unlock.ts` — if a device key + a DEVICE wrap matching the stored salt exist, derive the
  KEK with `kekDevicePassword` (password-only); else the PASSWORD floor. *UI:* a "trust / untrust this
  device" control in the unlocked state, and the unlock card preferring password-only when trusted with
  **"unlock another way"** → Secret-Key path. Tests: enrol→unlock-via-DEVICE round-trip (password only →
  Master Key recovered, using a mocked device adapter + real `kekDevicePassword`); wrong password → generic
  failure; **E1** device key absent → no DEVICE unlock, floor offered; multi-device salt-match picks the
  right wrap. **Done when:** `npm run check` green **and** a preview smoke shows, on a fresh instance:
  setup → unlock → **trust this device** → (reload / re-login) unlock with **only the password** → **untrust**
  → unlock falls back to password + Secret Key.
- [ ] **5 — verify + coverage.** Fill `docs/coverage/features/account/use-cases/uc.account.trust-device.yaml`
  (criteria → the web round-trip + backend slice + http boundaries; `gaps` for apple/android and the http
  authed-path), and add the `uc.account.trust-device` row to
  [platform-parity.md](../docs/crosscutting/platform-parity.md) (backend/http/web ✅, apple/android ◻).
  **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

A web user who has unlocked once with password + Secret Key can **trust the device** and thereafter unlock
with **only the password** — across reloads and fresh logins — backed by a non-extractable IndexedDB device
key; can **untrust** (which removes only that device's wrap and falls back to the floor); and can trust
**multiple** devices independently. The server holds only ciphertext wraps and never the device key, KEK,
Master Key, or Secret Key; the PASSWORD floor can't be deleted and is always reachable via "unlock another
way". Coverage for `uc.account.trust-device` is green and the parity matrix reflects it (backend/http/web).
Worker-cached re-lock, passkey/biometric, cross-device onboarding and native trust remain separate, planned
work. After merge, keep this file as a record (per the cr convention).
