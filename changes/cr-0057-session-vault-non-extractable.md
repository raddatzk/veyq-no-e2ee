---
id: cr-0057
title: Session vault uses a non-extractable key — stop parking the raw Master Key in sessionStorage (web)
status: done
satisfies: []
adrs: ["0005"]
invariants: [operator-blind-e2ee]
---

## Goal

Close the one inconsistency the cr-0054/0055 review left: the web "off" tier (cr-0055) stored the **raw**
Master Key in `sessionStorage`. Every other device tier (DEVICE, DEVICE_KEY, the "on" tier) uses a
**non-extractable** WebCrypto key so an XSS payload can *use* the key while it runs but cannot *exfiltrate* it —
and the raw bytes can additionally be written to disk by the browser's session-restore. Make "off" match: keep a
**non-extractable AES-GCM key in IndexedDB** and put only the **ciphertext** in `sessionStorage`.

This is essentially cr-0052's `vaultCache` mechanism (non-extractable key + ciphertext) with the **ciphertext**
moved to `sessionStorage` so it still dies on browser close — restoring the XSS-exfiltration + no-plaintext-on-disk
property cr-0055 traded away, without changing the user-visible behaviour.

## Why

- **Consistency / threat model.** The default tier was weaker than the opt-in: raw bytes are readable with a
  bare `sessionStorage.getItem` and survive reloads, whereas a non-extractable key is never exposed to JS.
- **Disk leak.** Chrome's "continue where you left off" persists `sessionStorage` to disk → the raw Master Key
  could land on disk, undermining "dies on browser close." Ciphertext + a non-extractable key make that moot.
- **No behaviour change.** off still survives a reload and is gone on browser close; on stays as-is.

## Design

- `cacheSessionVault(email, masterKey)`: generate a **non-extractable** AES-GCM key K (IndexedDB `veyq-session`),
  `iv = random(12)`, seal the Master Key under K, and store `{ email, iv, ciphertext }` (base64) in
  `sessionStorage`. The Master Key bytes never touch `sessionStorage`.
- `loadSessionVault()` (now async): read the `sessionStorage` record; if absent, drop the orphaned K (cleanup)
  and return null; else load K from IndexedDB and decrypt → `{ email, masterKey }`. Null on any failure.
- `clearSessionVault()` (now async): remove the `sessionStorage` record **and** delete K.
- Lifecycle: browser close clears `sessionStorage` → the ciphertext is gone and the orphaned non-extractable K
  (un-exfiltratable, useless without the ciphertext) is deleted on the next boot. The `cacheSessionVault` /
  `loadSessionVault` / `clearSessionVault` API turns async; callers (`unlock.restoreVaultFromCache`,
  `login.logout`, `boot`, `LoginView`) `await` it.

## Phases

- [x] 1 rework `sessionVault.ts` to the non-extractable-key + ciphertext model (reuse the shared `idbRun`);
  update `sessionVault.test.ts` (async + `fake-indexeddb`) to assert the Master Key never appears raw in
  `sessionStorage` and the enrol→load→clear round-trip + orphan-key cleanup. · Done when: `npm run check` green.
- [x] 2 thread the now-async API through `unlock.ts`, `login.ts`, `boot.ts`, `LoginView.vue`
  (+ `unlock.restore.test.ts`). · Done when: `npm run check` green.
- [x] 3 correct ADR 0005 §23 (non-extractable key + ciphertext, not raw bytes) and the `sessionVault.ts` header.
  · Done when: the three trust-chain guards green; a live off-reload smoke re-confirmed.

## Verify

`cd web && npm run check` green; the three guards green; a live browser smoke: off → reload stays in, and
`sessionStorage` holds only ciphertext (no raw key); browser-close still drops it. on unchanged.

## Definition of done

The "off" session vault never stores raw key material; an XSS payload can't exfiltrate the Master Key from it
and the browser can't persist it to disk in plaintext. Behaviour (off survives reload, dies on close) is
unchanged. Checks + guards green; ADR 0005 §23 corrected.

## Retrospective

Shipped the non-extractable-key + ciphertext-in-`sessionStorage` model; `npm run check` (173 web tests, incl. a
guard that the raw key never appears in `sessionStorage` + the orphan-key cleanup) + the three trust-chain guards
green. **Live re-smoke confirmed:** off-login → `sessionStorage` holds only `{email, iv, ciphertext}` (no raw
key) and IndexedDB `veyq-session` holds an **`extractable: false`** AES-GCM key → reload stays in the shell.

- The `cacheSessionVault`/`loadSessionVault`/`clearSessionVault` API turned async (IndexedDB) — threaded `await`
  through `unlock`, `login`, `boot`, `LoginView` and the two tests.
- Built on cr-0056's shared `idbRun`, so the new key store cost ~no new boilerplate.

**Why this was worth a CR rather than "good enough":** cr-0055 had quietly *regressed* cr-0052's
XSS-exfiltration resistance (non-extractable key → raw bytes) in the name of simplicity, and raw bytes in
`sessionStorage` can be persisted to disk by browser session-restore — so the default tier was weaker than the
opt-in. The lesson: when a "simplification" drops to raw key material, treat it as a security change, not a
cleanup — the convenience (survive reload, die on close) was achievable without ever exposing the key.
