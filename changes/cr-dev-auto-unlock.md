---
id: cr-dev-auto-unlock
title: Dev-only auto-unlock — boot the local dev app already unlocked from local seed creds (never in prod)
status: done
satisfies: []
adrs: ["0003", "0005"]
invariants:
  - "Operator-blind E2EE untouched in production — the auto-unlock is a DEV-SERVER-ONLY convenience. Its credential source (a Vite `apply:'serve'` middleware) does not exist in a prod build, and its client consumer is `import.meta.env.DEV`-gated (statically false in prod ⇒ dead-code-eliminated). A promoted guard proves the built `dist/` contains neither."
  - "No new crypto, no new key path — it calls the app's EXISTING `login` + `unlockMasterKey` with dev-seed creds (the same flow the e2e tests use), so it cannot unlock anything the real credential path couldn't."
---

## Goal

Give an operator (a human on a phone, or an agent that must not type passwords) a **hands-off, restart-surviving**
way to reach the unlocked local dev app — *without anyone entering the password into the login form*. On the **dev
server only**, an opt-in (`?devlogin`) makes `boot()` fetch the local dev-seed credentials and run the app's own
`login()` + `unlockMasterKey()` (then enrol the device key so it persists), landing on the gallery already
unlocked. This is a **dev convenience backdoor**, built so it is structurally impossible for it to reach a
production build: the credential endpoint is a `apply:'serve'` Vite middleware (absent from `vite build`), and the
client branch is `import.meta.env.DEV`-gated (tree-shaken from prod), with a guard that fails if the built `dist/`
contains either. It survives front-end reloads and backend restarts (via the normal remember-me tiers); after a
`down -v` wipe it re-establishes with one `bootstrap:user` + a `?devlogin` load.

## Context & constraints

- **Why this exists:** veyq is operator-blind E2EE — the vault unlocks only from password + secret key derived
  **client-side** ([ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)); there is no server-side
  "unlocked" state to hand out. So an agent that (by policy) never types passwords into a field cannot reach the
  unlocked app at all. This closes that gap for **local dev only**, reusing dev-seed material that is already
  plaintext on disk (`.run/.env`, `dev-seed.json`) and already reconstructed into unlocked vaults by the e2e tests.
- **The unlock flow to reuse (no new crypto):** `web/src/features/account/login/login.ts` `login(email, password,
  Persistent)` → session + remember cookie; then `web/src/features/account/unlock/unlock.ts`
  `unlockMasterKey(password, secretKeyB64)` → `openVault` → `setUnlocked(masterKey, identityPrivate)` in
  `web/src/app/vault.ts`. After both, `session.accessToken` **and** `vaultState.unlocked` are set — exactly the two
  the router guard checks (`web/src/router.ts` `requiresUnlock`). This is verbatim what `media.e2e.test.ts` does
  (login + unlockMasterKey from `dev-seed.json`); the dev-unlock is that flow, moved into `boot()`.
- **Persistence to piggyback:** `web/src/features/account/rememberMe/deviceKeyWrap.ts` `enrolDeviceKey(email,
  masterKey)` (the `DEVICE_KEY` tier, [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md)) stores a
  non-extractable key in IndexedDB `veyq-remember` + a server-side master-key wrap; `boot()` →
  `restoreVaultFromCache()` re-unlocks from it on later loads. So the dev-unlock enrols the device key once and
  normal boot restores it thereafter — and is **idempotent/self-healing**: it only fires when boot did not already
  restore a session, so it works whether or not the browser persisted state (survives a wiped browser profile too).
- **Seed source = `.run/.env` (the browsing tenant).** `bootstrap:user` writes `<repo-root>/.run/.env`
  (`VEYQ_EMAIL`/`VEYQ_PASSWORD`/`VEYQ_SECRET_KEY`, tenant **`veyq.localhost`** — the host we browse). It holds
  `WRAPPED_MASTERKEY` (still wrapped), so the **credential path** (`login` + `unlockMasterKey`) is the right reuse,
  not `vaultFromSeed` (which needs the raw Master Key that only `seed:dev`'s `127.0.0.1` `dev-seed.json` has). Using
  `.run/.env` keeps host↔creds matched (the documented 401 footgun otherwise).
- **The dev-only seam (the security spine):** mirror `mediaSwDevPlugin()` in `web/vite.config.ts` — a `Plugin` with
  `apply: 'serve'` + `configureServer(server)` adding a middleware serving `GET /__dev/seed-credentials` that reads
  `<repo-root>/.run/.env` **server-side** and returns `{ email, password, secretKey }` as JSON. Because the plugin
  is `apply:'serve'`, it is **never** part of `vite build` — the endpoint does not exist in prod. The dev server
  already binds `127.0.0.1` only.
- **No `import.meta.env.DEV` precedent yet** — this introduces the idiom (Vite statically replaces it with `false`
  in a build and tree-shakes the branch, the standard guarantee). The client consumer lives entirely inside
  `if (import.meta.env.DEV) { … }`.
- **Opt-in even in dev — never fires unannounced.** The auto-unlock triggers only on an explicit `?devlogin` query
  param (or a `veyq.dev-autologin` localStorage flag it sets so it survives in-browser navigations). A plain
  `npm run dev` is **unchanged** — the normal login screen shows; the maintainer's own flow (test the login, log
  in as a different user) is untouched.
- **Not operator-blindness-eroding in prod, by construction — and proved:** the CR rests on the structural claim
  "neither the credential endpoint nor the auto-unlock code is in a prod build". That is promoted to a **guard**
  (see phase 2), not left to inspection — this is a security backdoor, so the completeness proof is mandatory.

## Phases

- [ ] **1 web — the dev-server credential endpoint + the gated boot branch.**
  - `web/vite.config.ts`: add `devSeedCredentialsPlugin()` — `apply: 'serve'`, `configureServer` middleware for
    `GET /__dev/seed-credentials` reading `<repo-root>/.run/.env` (parse the dotenv), returning
    `{ email, password, secretKey }` JSON; 404/empty if the file or keys are absent (so it degrades on a machine
    that never seeded). Registered alongside `mediaSwDevPlugin`.
  - New `web/src/app/devAutoUnlock.ts` — a single exported `maybeDevAutoUnlock(): Promise<void>` whose **entire
    body is inside `if (import.meta.env.DEV)`**: if not already unlocked and the `?devlogin` opt-in is present,
    `fetch('/__dev/seed-credentials')` → `login(email, password, Persistent)` → `unlockMasterKey(password,
    secretKey)` → `enrolDeviceKey(email, masterKey())`; swallow+log any failure (never block boot). Set the
    localStorage opt-in flag so an in-app navigation keeps it.
  - `web/src/app/boot.ts`: after `restoreVaultFromCache()` (and only if still locked), `await maybeDevAutoUnlock()`.
  - **Done when:** `npm run check` green; on the dev server, `veyq.localhost:8081/?devlogin` boots straight to the
    gallery unlocked; a plain `veyq.localhost:8081` still shows the login screen.

- [ ] **2 web — the backdoor-cannot-ship guard (promoted, bite-proofed).**
  - `web/scripts/assert-no-dev-backdoor-in-dist.mjs`: after `vite build`, grep `dist/**` for the sentinels
    `__dev/seed-credentials` and a stable marker string from `devAutoUnlock.ts` (e.g. `VEYQ_DEV_AUTOUNLOCK`); exit
    non-zero if **either** appears. Wire it as a `verify:no-backdoor` npm script run right after `build` (and in CI).
  - A `vite.config` unit assertion that `devSeedCredentialsPlugin()` carries `apply: 'serve'` (serve-only by
    contract).
  - **Bite-proof:** temporarily remove the `import.meta.env.DEV` gate (or the `apply:'serve'`), `vite build`, and
    confirm the guard goes **red**; restore and confirm green (snapshot-and-restore, never `git checkout`).
  - **Done when:** the guard is green on the real build and red on the un-gated one; `npm run check` green.

## Definition of done

`npm run check` green; the dev-server opt-in unlocks hands-off and survives a reload + a backend restart; a plain
dev load is unchanged. The load-bearing **security** invariant — the backdoor is absent from any prod build — rests
on two independent structural gates (`apply:'serve'` credential source + `import.meta.env.DEV` consumer) **and** a
bite-tested guard that fails if the built `dist/` contains either sentinel. No contract/backend change; no new
crypto (reuses `login`/`unlockMasterKey`/`enrolDeviceKey`). A short note in `web/CLAUDE.md` documents the dev
backdoor, its opt-in, and its hard prod-exclusion.

**Explicitly out of scope / rejected:** exposing the creds via `VITE_`-prefixed client env (would bake them into
the bundle — rejected for the `apply:'serve'` middleware, which ships nothing); a `vaultFromSeed` key-injection
shortcut (needs the raw Master Key only `seed:dev` has, and bypasses the real unlock path — the credential path is
both simpler and closer to real behaviour); any auto-fire without the `?devlogin` opt-in.

## Retrospective

- **The guard is the whole legitimacy, and I proved it bites rather than assuming DCE works.** "`import.meta.env.DEV`
  is tree-shaken, so it's fine" is a *belief*; the whole safety of a credential-handling backdoor rested on it, so
  a belief wasn't good enough. The bite-test (force the gate on → build → both sentinels leak into `dist/` → guard
  red → restore → green) turned it into a proof. Concretely it also validated a design choice: the marker
  (`VEYQ_DEV_AUTOUNLOCK`) had to be a real literal in the source, or there'd be nothing for the guard to catch if a
  future edit removed the gate.
- **Honest framing — this is a rule-respecting solution, not a rule-bypass.** The credential rule bars *me* from
  typing a password into a field; here the *app* fetches local dev creds and runs its own `login`, so no password
  is ever typed by me. That honors the rule's intent (protect the user's real credentials; no new exposure — the
  creds were already plaintext in `.run/.env`) *only because* it's strictly dev-gated. If the gating failed, this
  would be a genuine credential-handling regression — which is exactly why the guard is load-bearing, not ceremony.
- **Grounding-first paid off — zero design rework.** The Explore map handed me the exact seam (`boot()` after
  `restoreVaultFromCache`), the exact reuse (`login` + `unlockMasterKey`, the e2e-test flow), and the exact
  dev-only precedent (`mediaSwDevPlugin`'s `apply:'serve'`). No new crypto, no new key path — the change is a thin
  reuse behind two gates. Building was mechanical.
- **Process:** German conversation / English code; waited for the explicit "los" between the CR draft and code;
  bite-tested the one load-bearing guard; delegated the surface read to a subagent per the working rules. One thing
  to watch next time: I introduced the repo's *first* `import.meta.env.DEV` gate — a new idiom — so the guard that
  proves it works is doubly worth having, since there's no established pattern to lean on.
