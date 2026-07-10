---
id: cr-0118
title: Password-protected shares — an operator-blind access-control gate (Argon2id token)
status: done
satisfies: [uc.sharing.share-password]
adrs: ["0037"]
invariants: [operator-blind-e2ee]
---

## Goal

An optional **password** on a share so a leaked link alone isn't enough. Per
[ADR 0037](../docs/decisions/0037-password-protected-shares.md) this is an **operator-blind access-control gate**,
**not** a crypto gate: the content stays E2EE under the unchanged `K_share` fragment, and the password gates **what
the server serves** — the same shape as the existing `contributorToken` (cr-0101). Set / change / remove / reveal
**in place**, same link; all roles. The honest limit (defends a leaked link against an *honest* operator, not a
malicious one) is documented, matching the passive-operator-safe web viewer.

## Context & constraints

- **Operator-blind — the raw password never reaches the server.** The client derives `pwToken = Argon2id(pw,
  pw_salt)` (reusing the conformance-pinned Argon2id in `web/src/shared/crypto/v1.ts`) and `pw_verifier =
  SHA-256(pwToken)`. The server stores `password_required` (plaintext flag) + `pw_salt` + `pw_verifier` +
  `sealed_password = symWrap(K_share, pw)` (AAD `share-password`, a management-only reveal copy). The server does
  **no** Argon2id — it only `SHA-256`-checks the presented token. It never sees `pw`, `K_share`, `K_mem`, or content.
- **Guest gating on every guest endpoint.** When `password_required`, the guest presents `X-Share-Password:
  base64(pwToken)`; the check folds into `activeShare(...)` so `resolveShare`, the gated blob GET, and the
  contributor/uploader endpoints all require it. A **locked** resolve (no token) returns only `passwordRequired` +
  `pw_salt` (no `wrap`/`sealedName`/`sealedNote`/media); a **valid** token → the full share; a **wrong** token →
  `403 share_password_incorrect`. Online guessing is per-IP rate-limited (existing).
- **In place, same link.** `K_share`/the fragment is never touched. `updateShare` (owner-gated, ADR 0036) gains a
  **tri-state** password patch: `passwordRequired = true` sets/changes it (fresh `pw_salt`/`pw_verifier`/`sealed_password`),
  `false` removes it (clears the fields), **omitted** leaves it untouched — so set / change / remove / add-later all
  keep the link. **Reveal:** the curator recovers `K_share` via the `K_mem`-recoverable `sealed_share_key` (cr-0115)
  and opens `sealed_password`.
- **All roles.** The gate fronts every guest endpoint, so a password protects VIEWER / CONTRIBUTOR / UPLOADER alike;
  the password field lives in the shared `ShareCreateDialog` (normal **and** uploader).
- **Verify:** backend `./gradlew check` (slice: locked resolve hides everything; valid token unlocks + wrong is
  403; the blob GET needs the token; set/change/remove via updateShare keeps the link; the manage list carries
  `sealed_password`); web `cd web && npm run check`; the three trust-chain guards; a live smoke (create a
  password-protected share → the share page prompts → wrong then right password → content; reveal + change in the
  manage dialog).

## Phases

- [x] 1 **contracts** — `CreateShareRequest` + `UpdateShareRequest` += `passwordRequired` (bool; on update the
  nullable tri-state), `pwSalt`, `pwVerifier`, `sealedPassword`. `PublicShare` += `passwordRequired` + `pwSalt`,
  and `wrap`/`sealedName` are now **nullable** (omitted in the locked state). `ShareSummary` += `passwordRequired`
  + `sealedPassword` (the reveal). `resolveShare` + `resolveShareBlob` grow an optional `X-Share-Password` header
  (param `SharePassword`) + a `403 share_password_incorrect`. · **Done:** regen green both sides.
- [x] 2 **backend** — V39 (`veyq_share += password_required, pw_salt, pw_verifier, sealed_password`). `ShareEntity`
  + `SharingStore.create`/`updateShare` (tri-state) / served in `listShares`. `SharingHandler` gained a
  `passwordCheck` (`SHA-256(pwToken) == pw_verifier`, constant-time, **no** Argon2id server-side) folded into
  `resolve` (→ `Locked`/`PasswordIncorrect`) + `resolveBlob`; the resource reads the header + maps the outcomes
  (locked shell / 403). · **Done:** `./gradlew check` = **293 tests** (+5 `SharePasswordSliceTest`: locked hides
  everything; valid unlocks + wrong 403; the blob GET needs the token; set/change/**remove**/**add-in-place** keep
  the link; the manage list carries `sealed_password`).
- [x] 3 **http** — a boundary case in `sharing.http`: an unknown share **with** an `X-Share-Password` header still
  404s (the password gate is after the existence check, so the header is not an enumeration oracle); the authed
  password happy-path is slice-proven (the boundary file can't create a member-scope share). · **Done.**
- [x] 4 **web** — `share.ts`: `derivePasswordToken` (Argon2id) + `readSharePassword` (K_share reveal) +
  `buildPasswordFields`; `createShare`/`updateShare` carry the password (tri-state `{set}`/`{remove}`);
  `resolveShare`/`openShareBlob` thread the token + surface `SharePasswordRequiredError`/`SharePasswordIncorrectError`.
  `ShareCreateDialog` gained an optional password field (set/change/remove + 👁 reveal on edit); `ShareDialog` a lock
  badge; `ShareView` a password-prompt state. i18n. · **Done:** `npm run check` = **556 tests** (+4 `share.test.ts`).
- [x] 5 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries the password
  fields after regen; the native password UI is each platform's own later CR.

## Status note (implemented 2026-07-10)

Shipped **contracts → backend → http → web**, all **automated** verification green: backend `./gradlew check` =
**293 tests** (+5 `SharePasswordSliceTest`), web `npm run check` = **556 tests** (+4 `share.test.ts` password
round-trips) + build, and the **five trust-chain guards** (coverage picks up `uc.sharing.share-password`). Refined
state in place: [ADR 0037](../docs/decisions/0037-password-protected-shares.md) (the access-control-vs-crypto
tradeoff, stated honestly) + [uc.sharing.share-password](../docs/features/sharing/use-cases/uc.sharing.share-password.md)
+ the [security-model](../docs/crosscutting/security-model.md) operational-metadata list.

**Design note.** Chosen with the user (who proposed it): the password is an **access-control gate**, not a
crypto-gate — it defends a leaked link against an **honest operator**, matching the passive-operator-safe web
viewer; the fragment stays `K_share`, so set / change / **remove** / **add-to-an-existing-share** all keep the same
link. The reveal (`sealed_password` under `K_share`) lets the curator look the password up later — operator-blind.

**Dialog UI (per user feedback).** In `ShareCreateDialog` the **note** and **password** are each an optional
**grouped card gated by a `ToggleSwitch`** ("Hinweis" / "Passwortschutz") — turning the toggle on reveals the field
*inside* the same lighter card. The password enforces a **client-side minimum of 10 characters** (`MIN_PW`; the
subtitle "Freigabe nur mit Passwort ab 10 Zeichen öffnen", an inline "Mindestens 10 Zeichen" error, and a disabled
save below it) — a UX convention only, since the server never sees the password. The role switcher now spans the
full dialog width. Live-verified: both toggle cards, the field-in-card reveal, and the 10-char gate.

**Live browser smoke — PASSED (2026-07-10, after the backend restart).** Created a password-protected share
(`geheim123`) → the share page resolved to the **locked** state ("Diese Freigabe ist passwortgeschützt", and the
Memory name **did not leak** — nothing readable without the password) → a **wrong** password → `403` + "Falsches
Passwort" re-prompt → the **right** password unlocked the resolve (the gate passed; the full render then hit the
**pre-existing cr-0113 demo-data 500** on the "test" Memory's non-UUID `share_identity_id`, unrelated to this CR).
The manage list showed the **"Passwort" lock badge**; editing the share **revealed** the stored password
(`geheim123`, decrypted via the `K_mem`-recovered `K_share`); and — proving the tri-state "untouched" path — a role
change in the same save **kept** the password. (Also: the role switcher now spans the full dialog width, a small CSS
rider.)
