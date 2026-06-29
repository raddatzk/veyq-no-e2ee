---
id: cr-0046
title: Reproducible dev seed — persist account material to a gitignored file (loadable by a harness)
status: done
satisfies: []
adrs: ["0003", "0006"]
invariants: [operator-blind-e2ee]
---

## Goal

Make the local dev seed **reproducible**. Today `web/scripts/seed-owner.mts` + `seed-tenant.mts` mint an account
and print its **random Secret Key once to stdout** — unrecoverable afterwards, so a later live smoke or unlock
(`unlockMasterKey(password, secretKey)`) can't reuse the account and must **re-seed a fresh tenant** each time
(the cr-0045 live-smoke pain point). And reading the invite link back from the backend **console log** is
fragile (ANSI colouring + an async flush race; needs the backend to log to a known file).

This CR persists the full seed to a **gitignored** `web/scripts/.seed/dev-seed.json` — both the **credentials**
(`email` / `password` / `secretKey`) **and the raw key material** (the Master Key + the serialized identity
private key) — so a harness can either `login()` + `unlockMasterKey()` **or inject the vault directly** (skip
the unlock Argon2). A `seed:dev` orchestrator brings up a known `{Server Owner + 127.0.0.1 tenant owner +
personal scope}` in one command against a fresh instance, and a `loadDevSeed()` loader + a vault-inject helper
make it usable from a node harness or the browser smoke. The invite hand-off is hardened with a **dev-only
invite file** written by the mailer (no console-log scraping, no race). Pure **dev tooling** — no product
behaviour, no contract change; the operator-blind wire path (`buildAccount` → only ciphertext + public keys)
is untouched.

## Context & constraints

- **Operator-blind is not weakened.** The server still only ever receives ciphertext + public keys — the seed
  scripts already use the real [`buildAccount`](../web/src/features/account/register/accountBirth.ts)
  ([ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)). The new file is a **client-side dev
  convenience** holding **throwaway** keys for a local instance — exactly the data a real user keeps in their
  Emergency Kit — **gitignored, never committed, dev/test only**. It is not at-rest key storage in any shipped
  surface. The dev-invite file is likewise dev/test-only (the mailer already refuses to log the secret in
  `LaunchMode.NORMAL`).
- **The Master Key is re-derived, not exported from product code.** `buildAccount` returns the wraps, not the
  raw Master Key; the seed script recomputes it locally from the PASSWORD wrap (`kekPasswordSecret(secretKey,
  password, salt)` → unwrap) — **no change to `accountBirth.ts`**.
- **Domain `127.0.0.1`** = the host the web dev server's `/api` proxy presents to the backend, so the seeded
  tenant resolves under the **normal** dev flow (and future smokes) with no Host override
  ([scopes-and-tenancy](../docs/crosscutting/scopes-and-tenancy.md), the active tenant is resolved from Host).
- **Invite mechanism unchanged** ([ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md)): the
  bootstrap secret still lives only in the URL fragment on the wire. The dev-invite file is a **second
  dev-only sink** of the same line the mailer already logs outside production — opt-in via a config path,
  empty by default (behaviour unchanged when unset).
- **`scripts/**` is outside the web typecheck scope** (`tsconfig.json` `include: ["src", "vite.config.ts"]`),
  so the new `.mts` run via `tsx`; `npm run check` is unaffected.
- **Verify:** `cd backend && ./gradlew test` (the mailer dev-file slice); a `seed:dev` run against a **fresh**
  instance (`docker compose -f infra/docker-compose.yml down -v && up -d`) produces `dev-seed.json`, and a
  loader round-trip (`login` + both `unlockMasterKey` **and** vault-inject) opens the vault; `npm run check`
  stays green.

## Phases

- [x] 1 backend — **dev-only invite file.** `LoggingTenantInviteMailer` also appends the invite as one JSON line
  (`{ownerEmail, token, expiresAt}`) to a file when `veyq.tenant.invite.dev-file` is set (empty default = no
  file, unchanged). The write sits **after** the existing `LaunchMode.NORMAL` early-return, so a production
  runtime never writes the secret. · Done when: `./gradlew test` green with a slice test (file written in
  dev/test, the line parses; absent when the config is empty).
- [x] 2 web — **seed file + raw keys.** `web/scripts/devSeed.mts`: the `.seed/` paths + read/merge/write of
  `dev-seed.json`, and `materializeKeys(built, password)` (re-derive the Master Key from the PASSWORD wrap +
  `serializeIdentityPrivate`). `seed-owner.mts` / `seed-tenant.mts` write their result (creds **+** raw keys)
  into the file (keyed `owner` / `tenants[domain]`) in addition to stdout; `seed-tenant` reads the invite from
  the **dev-invite file** (poll with a timeout, no ANSI). `.gitignore` `web/scripts/.seed/`. · Done when: a
  `seed-owner` + `seed-tenant` run against a fresh instance writes a complete `dev-seed.json`.
- [x] 3 web — **orchestrator + loader + docs.** `npm run seed:dev`: seed the Server Owner (if `setupRequired`)
  + a `127.0.0.1` tenant owner + ensure its **personal scope** (login → seal grant → `createPersonalScope`
  against `http://127.0.0.1:8080` so the Host resolves), recording `tenantId` + `scopeId`. `loadDevSeed()` +
  `vaultFromSeed(seed)` (returns `{masterKey, identityPrivate}` ready for `setUnlocked`). Point
  `media.e2e.test.ts` at the seed file when present (else the existing env vars). Document the reset+seed recipe
  in `web/CLAUDE.md` + `infra/README.md`. · Done when: `npm run check` green; a documented
  `down -v → up -d → seed:dev` yields a loadable identity that opens the vault both ways.

## Definition of done

`npm run seed:dev` against a fresh instance produces a gitignored `web/scripts/.seed/dev-seed.json` carrying a
known Server Owner + a `127.0.0.1` tenant owner (with personal scope) — credentials **and** raw key material —
and `loadDevSeed()` lets a harness open the vault via `login`+`unlock` **or** direct injection without
re-seeding. The invite hand-off no longer scrapes the console log. `./gradlew test` + `npm run check` + the
three doc guards green. No product/contract change; operator-blind wire path untouched; the seed file +
dev-invite file are dev/test-only and gitignored.

## Retrospective

**Outcome.** All three phases landed and were verified live: a `down -v → up -d → seed:dev` produced a complete
gitignored `dev-seed.json` (owner + `127.0.0.1` tenant owner + personal scope, creds **and** raw keys), and a
loader round-trip opened the vault **both** ways — credentials→unlock (re-derived Master Key matched the stored
one) and raw-key inject (the stored identity private key unwrapped the personal-scope grant). `./gradlew test`
+ `npm run check` + the three doc guards green. Three accumulated seeding hacks are now consolidated: the
once-printed Secret Key (→ persisted), the console-log invite scrape (→ a deterministic dev-invite file), and
the manual DB domain remap (→ automated in `seed:dev`).

**What went wrong (both directions).**
- *Recalled-memory dismissal (mine).* I explicitly read my own memory note that said `POST /api/tenants`
  **rejects bare-IP domains** and needs a hostname-then-DB-update, and judged it *stale* — then hit exactly
  that `400` mid-verification and had to add the remap. The recall was correct; the rule is that a recalled
  memory reflects what was true when written and should be **verified, not dismissed** — one `grep` of the
  domain `@Pattern` up front would have built the remap into `seed:dev` from the start.
- *Config idiom (mine).* `@ConfigProperty(defaultValue = "")` failed deployment (`ConfigurationException`); the
  established optional-config idiom (`Optional<String>`, as `BlobStore.publicEndpoint` already does) was right
  there. I reached for an empty-string default instead of copying the working pattern.
- *Direction (human, in our favour).* The human flagged this CR's root cause — "provide proper dev seed data,
  load the key from a file" — as a follow-up off cr-0045, which was a good, concrete catch that turned a
  recurring papercut into a one-command flow. No miss here; noting it because the retro asks for both directions.

**Next time.** When a recalled memory states a concrete constraint (an endpoint rejects input X), verify it
against the source (the spec's `@Pattern`/validation) **before** choosing an approach — treat the recall as a
lead to confirm, not a fact to discard. And when injecting optional config into a bean, copy the codebase's
existing `Optional<String>` pattern rather than an empty-string default.
