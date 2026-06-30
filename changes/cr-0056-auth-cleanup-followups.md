---
id: cr-0056
title: Auth/session cleanup — post-review follow-ups from cr-0054/cr-0055 (web/backend)
status: done
satisfies: []
adrs: ["0004", "0005"]
invariants: [operator-blind-e2ee]
---

## Goal

Pay down the quality/robustness findings the cr-0054/cr-0055 code review surfaced — no behaviour change beyond
one robustness fix. Each item was triaged; the two that the review proposed but that would **regress** behaviour
(per-keystroke `resolveDeviceState` reintroducing field flicker; folding the device-unlock "off" cache into
`enrolConvenienceWrap` which would wrongly re-enrol a still-valid wrap) are instead **documented as intentional**.

## Items

- [x] **A — shared IndexedDB helper (reuse).** `deviceKeyWrap.ts` and `trustDevice/deviceKey.ts` each hand-roll
  the same `openDb`/`run` transaction boilerplate (the "settle on COMMIT, reject on blocked" subtlety lived in
  two copies after `vaultCache.ts` was deleted). Extract `web/src/shared/idb.ts` (`idbRun(dbName, store, mode,
  op)`) and consume it from both. · Done when: both modules use the helper; `npm run check` green (deviceKeyWrap
  / trustDevice / sessionVault tests still pass).
- [x] **B — `SessionCookie.issue(token, persistent)` (altitude).** The "set one refresh cookie + clear the
  other" invariant is enforced by convention at 4 sites (login ×2, refresh, ...). Add
  `issue(token, persistent): Array<NewCookie>` that returns the chosen cookie **and** the cleared-other, so a
  write site can't set one without clearing the other. Use it in `LoginResource` + `TokenResource`. · Done when:
  `./gradlew check` green (the session/persistent + clear-the-other + logout slice tests pass unchanged).
- [x] **C — `enrolDeviceKey` upload-then-delete (robustness, #2).** Today it deletes the prior wrap (server +
  local) *before* uploading the replacement, so a failed upload loses the tier. Reorder: upload the new wrap,
  persist the local record, **then** drop the prior server wrap — a transient upload failure leaves the prior
  wrap intact. · Done when: `npm run check` green; the replace + rollback tests updated to the new ordering.
- [x] **D — micro-dedup + intent comments (#3/#4/#7).** Remove the duplicate `sessionReady = false` reset
  (the `watch(email)` already does it; `onEmailChange` keeps only `resolveDeviceState`). Add comments explaining
  (a) why `resolveDeviceState` is blur-based not per-keystroke (avoid Secret-Key-field flicker), and (b) why the
  device-unlock "off" path caches the session vault inline rather than via `enrolConvenienceWrap` (the DEVICE
  wrap is already valid — no re-enrol). · Done when: `npm run check` green.

## Verify

`cd web && npm run check` + `./gradlew -p backend check` green; the three trust-chain guards green. No behaviour
change except item C (a previously-lost tier now survives a transient upload failure); the cr-0054/0055 live
behaviour (off survives reload, on opens straight in, logout clears both cookies) is unchanged.

## Definition of done

The duplicated IndexedDB boilerplate is single-sourced, the one-refresh-cookie invariant is enforced by a single
`issue()` API, `enrolDeviceKey` no longer drops the tier on a transient upload failure, and the intentional
split points carry comments. All checks + guards green.

## Retrospective

All four items landed; `./gradlew check` + `npm run check` (171 web tests) + the three trust-chain guards green.

- **A** — `web/src/shared/idb.ts` now owns the open/transaction/settle-on-commit logic; `deviceKey.ts` and
  `deviceKeyWrap.ts` each shrank to a one-line `run` wrapper over `idbRun(DB_NAME, STORE, …)`.
- **B** — `SessionCookie.issue(token, persistent)` returns the chosen cookie **plus** the cleared-other; login
  (×2 + legacy `remember`) and refresh all route through it, so "exactly one refresh cookie" is now structural,
  not a convention a new write site could forget. `persistent()/session()/clearedX()` stay as its building
  blocks (+ logout's `cleared()`).
- **C** — `enrolDeviceKey` now uploads + records the new wrap, then drops the prior one; a transient upload
  failure keeps the existing remember-me wrap instead of leaving the device with none. The replace test was
  renamed (behaviour assertion unchanged — it never asserted call order); rollback test unaffected.
- **D** — dropped the duplicate `sessionReady = false` (the `watch` owns it; `onEmailChange` keeps only the
  blur-time `resolveDeviceState`). **Deliberately NOT merged** (the review's literal suggestion would regress):
  per-keystroke `resolveDeviceState` would flicker the Secret-Key field, and folding the device-unlock "off"
  cache into `enrolConvenienceWrap` would wrongly re-enrol a still-valid DEVICE wrap — both now carry comments
  explaining the split.

**Note / next time:** a review's "simplify by merging X and Y" is a hypothesis, not a verdict — two of the five
findings were intentional splits whose merge regresses behaviour. Triage each against *why* the split exists
before collapsing it; documenting the intent is the right close for those, not a refactor.
