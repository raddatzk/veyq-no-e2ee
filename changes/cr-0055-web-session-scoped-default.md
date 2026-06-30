---
id: cr-0055
title: Web session-scoped default — "off" survives a refresh, dies on browser close (web/backend)
status: done
satisfies: [uc.account.remember-this-device]
adrs: ["0004", "0005"]
invariants: [operator-blind-e2ee]
---

## Goal

Align the **no-"remember me"** web experience with the conventional web norm: a session that **survives a page
refresh** and is dropped when the **browser is closed** (plus the server-side token timeout, the real backstop) —
instead of cr-0052/cr-0054's behaviour, where "off" keeps the refresh token only in memory and is therefore lost
on the first reload. "Remember me" stays the persistent tier (survives a full browser restart).

User-facing result:

| Action | **off** ("until I close the browser") | **on** ("stay signed in") |
|---|---|---|
| Reload (F5) | stays in | stays in |
| Close + reopen the browser | re-auth — **email + password only** (no Secret Key) | stays in (opens straight in) |
| Secret Key | only the **first** sign-in per device | only the **first** sign-in per device |
| Untrusted machine | private/incognito window | — |

## Context & constraints

- **Two things must survive a reload, not one.** E2EE means a restored *session* (refresh token) is not enough —
  the **Master Key** lives only in memory and is gone on reload, so a session cookie alone would still force the
  password to re-unlock. To match the norm ("no retype on reload"), the vault key must persist for the browser
  session too.
- **The three transports (backend).** Today the refresh token is a **persistent cookie** (remember on) or a
  **body token** (everything else). Add a **session cookie** (no `Max-Age` → dropped on browser close) for web
  "off". The kind must be **preserved across rotation**, and the server can't read a cookie's own `Max-Age` back —
  so use **two cookie names** (`veyq_refresh` persistent / `veyq_refresh_session` session); refresh re-sets the
  same-named cookie; logout clears both. Native is unchanged (body token).
- **The vault for "off" = `sessionStorage`.** The unlocked Master Key is cached in `sessionStorage` (survives
  reload, cleared on browser close), so a reload re-opens the vault with **no password**. "On" keeps its
  IndexedDB `DEVICE_KEY` wrap (survives restart); "off" additionally keeps its **password `DEVICE` wrap** so that
  *after* a browser close the next sign-in is email + password (never the Secret Key).
- **Conscious security trade-off (ADR 0005 addendum).** Within an open browser session "off" is **no longer
  password-gated** — the `sessionStorage` key re-opens the vault. This is the standard web-session posture,
  bounded by the browser session (cleared on close) and **no worse than the already-unlocked in-memory vault** an
  XSS payload could read while it runs. The Master Key sits in `sessionStorage` as raw bytes (it can't hold a
  non-extractable WebCrypto key); the exposure window is the live session only. Operator-blind is untouched — the
  server still holds only ciphertext + an opaque cookie. Acceptable for a **memory-sharing** app (ADR 0005 §14),
  recorded explicitly.
- **The signal is the client, not the server.** The web tells the server which cookie it wants via a new
  optional `sessionScope` (`SESSION` | `PERSISTENT`) on login-verify; native omits it (→ body token). `remember`
  stays in the contract for backward compatibility (native + legacy) and is honoured when `sessionScope` is
  absent.
- **Verify:** `cd web && npm run check` + `./gradlew -p backend check` green; the conformance test + the three
  trust-chain guards green; a browser smoke: off → reload stays in (no prompt) → close+reopen → email+password
  (no Secret Key); on → reload + restart both open straight in; logout drops the session (both cookies cleared)
  and the `sessionStorage` vault.

## Phases

- [x] 1 session-cookie transport (contracts + backend) — added the optional `sessionScope` enum to
  `LoginVerifyRequest`; regenerated. `SessionCookie` gained a `session()` builder (no `Max-Age`, name
  `veyq_refresh_session`), a dual-name `read()` reporting persistent-vs-session, and `cleared()` for **both**
  names. `LoginResource` maps `sessionScope` (by wire value) → cookie kind (PERSISTENT → persistent, SESSION →
  session, else legacy `remember`/body); `TokenResource` re-sets the **same kind** on rotation; `SessionResource`
  clears both. · Done: `./gradlew check` green; `LoginSliceTest` covers SESSION → a session cookie (Max-Age -1)
  that rotates as a session cookie, PERSISTENT → a Max-Age cookie, logout clears both.
- [x] 2 session vault (web) — new `rememberMe/sessionVault.ts`: cache/load/clear the Master Key (+ email) in
  `sessionStorage`. `login()` now takes `scope` and sends `sessionScope`; the web is fully cookie-based (no body
  refresh token kept). · Done: `npm run check` green; `sessionVault.test.ts` round-trips cache → load → clear and
  tolerates corrupt/absent storage.
- [x] 3 wire the two tiers (web) — `LoginView` auto-enrol: **off** → password `DEVICE` wrap **+** session-vault
  cache (+ `sessionScope: SESSION`; the returning-trusted-off path refreshes the session vault too); **on** →
  `DEVICE_KEY` wrap + `sessionScope: PERSISTENT` (+ clears the session vault). Boot restore tries the session
  vault first (off), then the `DEVICE_KEY` wrap (on); logout + the no-session boot path clear the session vault.
  · Done: `npm run check` green (168 tests).
- [~] 4 coverage + verify — updated
  [uc.account.remember-this-device.yaml](../docs/coverage/features/account/use-cases/uc.account.remember-this-device.yaml)
  (the "off" criterion now: survives reload, password-only after close; backend session-cookie + web session-vault
  refs). The three trust-chain guards green. **Live browser smoke left to the user** (their request — they test
  in parallel): off → reload stays in / close+reopen → email+password (no Secret Key); on → reload + restart open
  straight in; logout drops both cookies + the session vault.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Session-scoped "off" (survive reload, die on close) | Status |
|---|---|---|
| backend / contracts | session cookie (`veyq_refresh_session`) + `sessionScope` enum (opaque) | ◻ this CR |
| web | session cookie + `sessionStorage` vault for "off"; persistent + `DEVICE_KEY` for "on" | ◻ this CR |
| apple / android | native is body-token + its own keystore tiers; no browser-session concept | ◻ **N/A (native)** |

## Definition of done

A web device without "remember me" stays signed in across a page reload (no prompt, no password) and is signed
out when the browser closes; the next launch then takes **email + password only** (no Secret Key) via the
device wrap. "Remember me" still opens straight in across a full browser restart. Logout clears both refresh
cookies + the session vault. `npm run check` + `./gradlew -p backend check` + the conformance test + the three
guards green; the smoke confirmed. The session-within-browser password-relaxation for "off" is recorded in
ADR 0005.

## Retrospective

**What shipped.** Web "off" is now the conventional session tier: a **session refresh cookie** (no `Max-Age`,
`veyq_refresh_session`) + the Master Key in **`sessionStorage`** → survives a reload, dropped on browser close,
and (via the kept `DEVICE` wrap) password-only after a close. "On" stays persistent (cookie + `DEVICE_KEY`).

**Design calls during build:**
- **Two cookie names, not one.** A cookie can't report its own `Max-Age` back, so rotation can't know whether to
  re-set a session or persistent cookie from the value alone. Encoding the lifetime in the **name**
  (`veyq_refresh` / `veyq_refresh_session`) makes `TokenResource` re-set the same kind and `logout` clear both.
- **`sessionScope` enum, `remember` kept.** Native sends `remember` (body token); changing it would break native
  models. Added an optional `sessionScope` (`SESSION` | `PERSISTENT`) that the web sends and that takes
  precedence; `remember` stays for backward compat (honoured only when `sessionScope` is absent). Backend matches
  by `.value` so the generated enum constant name doesn't matter.
- **Raw key in `sessionStorage`.** It can't hold a non-extractable WebCrypto key, so the Master Key is raw bytes
  there — a conscious, bounded relaxation (ADR 0005 §23): no worse than the already-open in-memory vault, cleared
  on close, operator-blind untouched. "On" keeps the non-extractable IndexedDB key (never raw).

**Verification.** `./gradlew check` (new session-cookie slice tests) + `npm run check` (168 tests, incl.
`sessionVault.test.ts`) + the three trust-chain guards green. The live three-state browser smoke was **delegated
to the user** (they test in parallel); if it surfaces anything, iterate as with cr-0054.

**Note.** Native (apple/android) is body-token only and has no browser-session concept — N/A, no parity action.
