---
id: cr-e2e-orchestrator
title: The web e2e orchestrator — one script, a live backend, the four surviving suites green in CI
status: planned
satisfies:
  - uc.memory.cover
  - uc.spaces.add-group-member
  - uc.spaces.manage-group-members
  - uc.spaces.remove-tenant-member
adrs: ["0013", "0040", "0050"]
invariants:
  - Operator-blind E2EE — the e2e suites are the only tests that observe the real wire; weakening one to make it
    pass is the failure mode this CR must not enable.
  - A test that no CI run executes makes no coverage claim (ADR 0040) — so the coverage refs return only after a
    green CI run, never on a local one.
  - Self-hostable by default — the orchestrator brings up the *bundled* stack from `infra/docker-compose.yml`; it
    may not depend on anything a self-hoster does not have.
---

## Goal

Write `scripts/run_web_e2e.py` — the missing orchestrator that brings up a real backend and a real object store,
then runs `web`'s four `*.e2e.test.ts` suites one file at a time against it — and turn the `web-e2e` CI job back
on. Today those suites are gated on `VEYQ_E2E` and every CI run skips them, so the four coverage refs they used
to carry were removed; [`docs/ai/README.md`](../docs/ai/README.md) § What counts as verified records the deal:
*"the day it is green, the refs come back"*. This CR is the only thing that discharges that obligation. It also
unblocks [`inbox.md` #102](../docs/inbox.md) (the wire assertion), whose oracle — the server-observable bytes —
only exists once a real flow runs against a real backend and a real store.

Scope is deliberately the **four suites that exist**, not the ~30 the pre-reset tree had. The orchestrator is the
deliverable; breadth is a later CR and gets cheap once the harness runs.

## Context & constraints

- The durable statement of the debt: [`docs/architecture/README.md`](../docs/architecture/README.md) §11 (*"the
  highest-value item here"*). The coverage rule it rests on: [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)
  — and its **Correction (2026-07-14)**, which records that the coverage table once claimed `run_web_e2e.py` ran
  when it did not. **This claim has already been false once.** The green must be observable in CI, never asserted
  in prose.
- The taxonomy already names the file: [`docs/coverage/README.md`](../docs/coverage/README.md) — *"a web suite run
  against a live backend (`run_web_e2e.py`) is e2e"*. Today the taxonomy names a file that does not exist.
- A byte-path store is a hard instance requirement, not an optional feature:
  [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §1 and
  `backend/src/main/kotlin/com/veyq/app/shared/blobstore/BlobStoreStartupCheck.kt`. There is **no in-memory escape
  hatch for a booted jar** — outside `%test` a blank endpoint aborts startup.
- The server-managed config-secret KEK: [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md)
  amendment. Its default path is **relative to the process CWD**, which N boots from the repo root would share.
- The bundled stack this must use: [`infra/README.md`](../infra/README.md) + `infra/docker-compose.yml`.
- Tenant resolution is server-side from the request `Host`: [`web/CLAUDE.md`](../web/CLAUDE.md) § the two host
  conventions. A wrong host surfaces as a `401` at `POST /api/login/verify`, not as a crypto bug.

**The interface is already specified by the dead job** — [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
§ `web-e2e`. Read it as the contract, not as history: no args, invoked from the repo root; no `env:`, no `with:`,
no `services:` (so the orchestrator supplies DB + store itself); no `pip install` anywhere in the job (so
**stdlib only** — the `pyyaml` install lives in `trust-chain.yml`, a different job); Java 21 + Gradle + Node 22
already provisioned; the fast-jar built **once** before the script runs; `npm ci` already done.

This CR **supersedes `changes/scoping-e2e-orchestrator.md`**, whose measurements are folded in below. Phase 0
deletes it and repoints the two files that cite it — `architecture/README.md` §11 (a markdown link, so
`validate_doc_links.py` goes red if the delete lands alone) and the `ci.yml:78` prose comment.

## Decisions — settled, do not relitigate

- **A · The recovered skeleton comes into the tree.** `scripts/run_web_e2e.py` was not lost to the greenfield
  reset; commit `5432372d` ("cleanup", overwhelmingly `.derivedData/` junk) deleted it. It lands as its own first
  commit, unported, then the port lands as the second — so the diff shows what was reused and what is new, and no
  commit hash is left doing a doc's job (`docs/ai/README.md` rule 5). Locator in Phase 0.
- **B · A declared `%e2e` profile**, not an env-override pile and not `-Dquarkus.profile=dev`. The relaxations an
  e2e run needs are then *declared in the repo* where a reader can see them, and the script stops carrying policy.
  `%dev` is rejected because it is maintained for the IntelliJ flow — a change there would silently move CI.
- **C · One bootstrap contract: self-bootstrapping.** The three memory suites stop reading
  `web/scripts/.seed/dev-seed.json` and cold-birth their own server owner + tenant, the way `scopeRotation`
  already does. This deletes the seeder dependency from the suites, the numeric-domain hack and the
  `docker exec veyq-mariadb … UPDATE veyq_tenant` DB poke from the CI path, and lets one host convention
  (`veyq.localhost` — a valid dotted domain *and* RFC 6761 loopback) serve every suite. `web/scripts/seed-dev.mts`
  itself is **not** deleted: it serves the local IntelliJ dev flow.

  **The credential env fallbacks go with the seed file.** All three memory suites read `VEYQ_EMAIL` /
  `VEYQ_PASSWORD` / `VEYQ_SECRET` as a layer *above* `dev-seed.json` (`media.e2e.test.ts:42-44`,
  `memoryCover.e2e.test.ts:38-40`, `memoryLocation.e2e.test.ts:44-45`), and they are dead the moment a suite
  births its own actor. Leaving them is the second bootstrap path wearing a different name — the thing this
  decision exists to remove. The surviving env contract is exactly the one `scopeRotation` already reads:
  `VEYQ_E2E`, `VEYQ_BASE`, `VEYQ_HOST`, `VEYQ_STAMP`.
- **D · The four surviving suites**, not restored breadth.
- **E · Closed already.** The same `cleanup` commit deleted the root `Dockerfile`; it was recovered and ported in
  `e4fe9f0b`. Nothing left to do here — recorded so the next reader does not re-open it.

## Substrate

Every `path:line` fact below was measured on branch `starting-something-new` at **`e9045037`**, working tree
clean, trust chain **16/16 green** (`for f in scripts/validate_*.py; do python3 "$f"; done` — the three
`mutation_meta_check*.py` are outside that glob by design). Anything not measured is marked `[inferred]`.
Re-measure before trusting: the whole point of the locators below is that they re-run.

## Locators — run these, do not trust the prose

```bash
# The suites, and the environment contract they actually read.
find web/src -name '*.e2e.test.ts'
grep -rn 'process.env.VEYQ' web/src --include='*.e2e.test.ts'
```

```bash
# Second angle, keyed on the OTHER side of the same contract: what the skeleton passes in.
# The disagreement IS the finding — it passes VEYQ_API_BASE, the suites read VEYQ_BASE.
git show a838b794:scripts/run_web_e2e.py | grep -n 'VEYQ_\|frontend'
```

```bash
# What %test relaxes, vs. what the shipped defaults are. The disagreement names the knobs %test does NOT
# touch but a live e2e run still hits — which is what decides whether %e2e can be a copy of %test.
grep -n 'ratelimit\|rate\.\|cookie.secure\|config-secret' backend/src/main/resources/application-test.properties
grep -n 'ratelimit\|rate\.\|cookie.secure' backend/src/main/resources/application.properties
```

```bash
# What a LaunchMode.NORMAL boot silently defaults — the trap is a default that resolves against the CWD.
grep -n -B3 '@WithDefault' backend/src/main/kotlin/com/veyq/app/shared/config/VeyqConfig.kt
```

```bash
# Guard-anchor sweep, before reshaping anything (changes/README.md § before reshaping a symbol).
grep -rln 'application-\|properties\|profile\|e2e' scripts/*.py
grep -rn 'scoping-e2e-orchestrator\|run_web_e2e' --include='*.md' --include='*.yml' --include='*.py' .
```

```bash
# Where the four coverage refs go — the coverage tree is SEPARATE from the use-case specs.
grep -rn 'scopeRotation\|memoryCover' docs/coverage/
find docs/features -name '*.yaml'      # → empty; specs are .md, refs are docs/coverage/**/*.yaml
```

Outcome of the guard-anchor sweep, **re-measured at `e9045037`** — and the first recorded outcome was wrong, so
trust the command, not this paragraph. The sweep matches four guards: `validate_coverage.py`,
`validate_field_visibility.py`, `validate_maxlength_storage.py`, `validate_scope_model_vocabulary.py`. An earlier
pass recorded `validate_ratelimit_core.py` + `validate_guard_gaps.py`, which match **nothing** in this sweep. The
conclusion survived the bad evidence, and that is exactly why the error is worth keeping on the page: a right
answer resting on wrong evidence re-derives to nothing, and this CR's whole posture is that *this claim has
already been false once*.

What the sweep is really asking is answered on a **second angle** — not "which guard mentions the word" but
"which guard reads the file":

```bash
grep -rn 'resources' scripts/*.py    # → validate_maxlength_storage.py:48 only, and it points at db/migration
```

No guard reads `backend/src/main/resources/application*.properties`, so a new `application-e2e.properties` trips
none of them; three of the four word-matches anchor on OpenAPI `components.schemas.*.properties.*`, which a
Quarkus profile file never touches. `e2e` is still a `KNOWN_LEVELS` entry in `validate_coverage.py:55`, so Phase 6
is a pure ref addition with no schema change.

## The five forks, and how each is settled

**F1 · The store is mandatory and cannot be a GH Actions service container.** Garage needs `/etc/garage.toml`,
which compose bind-mounts from the repo, and service containers start before checkout `[inferred, Actions
start-ordering]`; the official image is shell-less `scratch` (which is why `infra/garage-init.Dockerfile` exists);
bootstrap is a mandatory 5-step sequence in `infra/garage-init.sh`; the init sidecar is built locally from the
repo. → the orchestrator shells out to `docker compose -f infra/docker-compose.yml --profile s3 up -d`, reusing
the **two-step wait** idiom from `.run/Bootstrap DB + Garage.run.xml` (a single `--wait` on the whole project
exits 1, because `garage-init` exits even on success and `--wait` scores any exit as failure). `garage-init.sh`
is idempotent and self-heals after `down -v`.

**Correction to the scoping doc, measured here:** `mariadb` carries **no `profiles:` key** — it is in compose's
default set, so `--profile s3 up -d` already brings up mariadb + garage + garage-init + traefik. Decision B needs
**no extra container**, and `MARIADB_ROOT_PASSWORD` exists, so a per-file `CREATE DATABASE` is executable as root.

**And the port map, because Phase 3 boots N backends against a stack that is already listening:** the bundled
services publish 3306 (mariadb), 19000 + 19003 (garage), 18080 (traefik), 11025 + 18025 (mailpit — `--profile
mail`, not started here). **8080 is free**, so the sequential boots do not collide with the stack they test
against, and the suites' default `http://veyq.localhost:8080` reaches the backend *directly* — traefik is not in
the path for the API, only for same-origin blob access the `%dev` flow needs and this one does not.

**F2 · A fast-jar runs `%prod`, and `%prod` is hostile to a test run.** Datasource is env-only; the store
endpoint is unset (⇒ F1's abort); `veyq.session.cookie.secure=true` breaks plain-http "remember me"; the auth
limiter is 60/60 s, and a 4-actor suite doing real Argon2id logins against that is a coin flip.

And **"a fresh in-mem DB per file" — the design line in `ci.yml`'s own comment — does not work.**
`quarkus.datasource.db-kind` is a **build-time** property baked at augmentation from
`application-prod.properties`, so a runtime `QUARKUS_DATASOURCE_DB_KIND=h2` is ignored and the *MariaDB* driver
rejects the URL:

```
Caused by: java.sql.SQLException: Driver does not support the provided URL: jdbc:h2:mem:smoke;MODE=MariaDB
```

→ one MariaDB (already up, per F1) + `CREATE DATABASE veyq_e2e_<n>` per file. That is a fresh instance in the
only sense the suites care about: `setupRequired === true`. One container, no second jar, `ci.yml`'s "build once"
intact — and that stale comment gets corrected in Phase 5.

**The same mechanism binds the `%e2e` profile itself: it can carry only *runtime* properties.** Augmentation runs
under `%prod` for `quarkusBuild`, so a **build-time** key written into `application-e2e.properties` is read by
nobody — `[inferred: the fixed-at-augmentation behaviour measured just above for the env var, applied to the
profile file rather than the environment]`. Consequence for Phase 1, stated so it is not mistaken for the reason
the run works: do **not** re-declare `quarkus.datasource.db-kind` in `%e2e`. It would be inert, and the boot
succeeds precisely because `%prod` already baked `mariadb`. Every knob Phase 1 does set — `jdbc.url`, the four
limiter groups, `cookie.secure`, the blob-store block, `config-secret-file` — is runtime, so the profile reaches
all of them.

**F3 · Two incompatible bootstrap contracts.** `scopeRotation` asserts `setupRequired === true` and
self-bootstraps; the three memory suites read the seed file, whose producer reaches into the dev DB with
`docker exec` because `POST /api/tenants` rejects a numeric domain. Settled by Decision C.

The scoping doc estimated the convergence at *"~15 lines per suite"* `[was inferred]`. **Measured:** the
reusable block is ~14 lines for a single actor, but it pulls eight symbols from five modules (`accountBirth`,
`api/client`, `admin/createTenant/tenants`, `app/session`, `app/vault`). Copying that three times is the wrong
answer — Phase 2 extracts a shared helper and migrates `scopeRotation` onto it too, so the repo does not end up
with two bootstrap paths.

**F4 · The dev-invite file does not exist for a booted jar.** `TenantInviteMailer` early-returns before the
dev-file append in `LaunchMode.NORMAL` `[inferred: a quarkus-run.jar boot is NORMAL — from the mailer's own
docstring naming that mode as production]`, so `VEYQ_TENANT_INVITE_DEV_FILE` and `awaitInvite()` are unusable
here. Not a problem: `bootstrapSecret` is `required` on the HTTP response in
[`contracts/api/tenants.openapi.yaml`](../contracts/api/tenants.openapi.yaml), which is the path `scopeRotation`
already takes. Named because inbox #13's follow-up weighed making it optional and settled **won't-do** — that
decision is what keeps this path open. If it is ever reopened, this breaks.

**F5 · `fetch` strips a manual `Host`.** `web/scripts/bootstrap-user.mts` uses raw `node:http` specifically
because undici strips a manually set `Host`. → the base URL must carry the tenant domain for real
(`http://veyq.localhost:8080`). Converging on Decision C makes this disappear rather than needing a workaround.

## Four traps the scoping doc did not have

1. **`veyq.security.config-secret-file` defaults to `config-secret.key` relative to the process CWD**, and the
   server generates it `0600` on first start (ADR 0050 amendment). N boots from the repo root would share one
   file and leave an untracked key beside the repo. Every boot gets its own path under a per-run temp dir.
2. **`veyq.blob-store.public-endpoint` must stay unset in `%e2e`.** `%dev` signs presigned URLs for
   `http://127.0.0.1:5173` so the Vite proxy forwards them same-origin. There is no Vite server here;
   `BlobStore` falls back to the real endpoint when it is blank. Set it wrong and `media.e2e` fails in the
   **PUT**, not at boot — the most expensive place for this mistake.
3. **`VEYQ_STAMP` exists and must be set per run.** `scopeRotation.e2e.test.ts` reads it to *"vary
   emails/usernames per run so re-runs on a non-fresh DB don't collide"* — and it is also what keeps the
   per-recipient invite budget (`3` per recipient / `20` per tenant per `3600` s) from tripping on a re-run.
   Note which knobs `%test` does **not** relax: the `user` budget and all three invite budgets. `%e2e` is
   therefore not a copy of `%test`.
4. **`VEYQ_GEOCODER` must stay unset — as a decision, not by omission.** `memoryLocation.e2e.test.ts:60` reads it
   and, when set, makes a **real Nominatim call** to name the medoid. Unset, the suite drops exactly one assertion
   — that the geocoder returned levels — and keeps every assertion about *veyq*: the operator-blind seal, the
   read-modify-write date preservation and the "no plaintext in the ciphertext" checks all run unchanged, with
   `nameSource: 'manual'`. Nothing the coverage refs of Phase 6 claim depends on it. So the orchestrator leaves it unset,
   for two reasons worth writing down: a third-party network call makes a CI job flaky, and a harness that reaches
   the public internet contradicts the self-hostability invariant this CR is bound by. Phase 3 states the choice,
   so the next reader does not "fix" a knob that is off on purpose.

## Phases

- [x] **0 · scaffolding** — Land `git show a838b794:scripts/run_web_e2e.py > scripts/run_web_e2e.py` as its own
      commit, unported. Delete `changes/scoping-e2e-orchestrator.md` and repoint its two citers
      (`architecture/README.md` §11 link, `ci.yml:78` prose) at this CR **in the same commit**.
      · Done when: `python3 scripts/validate_doc_links.py` and `python3 scripts/validate_changes.py` green.

- [x] **1 · backend** — New `backend/src/main/resources/application-e2e.properties`: datasource (URL overridable
      per boot via env), `veyq.blob-store.{endpoint,access-key,secret-key}` at the bundled Garage,
      `veyq.session.cookie.secure=false`, the auth + user + three invite budgets, `veyq.share.password.rate.*`,
      and `veyq.security.config-secret-file`. `public-endpoint` deliberately **unset**, with the comment saying
      why (trap 2). A header comment stating that this profile ships inside the jar exactly as `%dev`/`%test` do
      — so `-Dquarkus.profile=e2e` in production would relax cookie security and the limiters. No new risk class;
      stated rather than left to be discovered.
      · Done when: `cd backend && ./gradlew check` green, and a hand boot with `-Dquarkus.profile=e2e` reaches
      `/q/health/ready` against the compose stack.

- [x] **2 · web** — Extract the cold-birth bootstrap out of `scopeRotation.e2e.test.ts` into a shared e2e test
      helper; migrate `scopeRotation` onto it (no second bootstrap path), then converge
      `media.e2e.test.ts`, `memoryCover.e2e.test.ts` and `memoryLocation.e2e.test.ts` onto it — they stop
      reading `web/scripts/.seed/dev-seed.json` **and** the `VEYQ_EMAIL`/`VEYQ_PASSWORD`/`VEYQ_SECRET` fallbacks
      above it (Decision C: one bootstrap contract means one, not one plus a credential channel).
      Fix the stale header comment in `scopeRotation.e2e.test.ts`
      claiming the tenant domain is `localhost` while the code defaults to `veyq.localhost`. `seed-dev.mts`
      untouched; if its numeric-domain remap turns out to have no remaining consumer, that is a **separate**
      commit with its own locator, not a silent deletion.
      · Done when: `cd web && npm run check` green (the four still *skip* here — that is correct), and each of
      the four passes by hand: `VEYQ_E2E=1 npx vitest run <file>` against the stack.

- [x] **3 · the orchestrator** — Port `scripts/run_web_e2e.py`: stdlib only, no args, run from the repo root.
      Compose up `--profile s3` with the two-step wait → per e2e file: `CREATE DATABASE veyq_e2e_<n>`, a fresh
      boot with `-Dquarkus.profile=e2e` + its own config-secret path + a per-run `VEYQ_STAMP`, poll
      `/q/health/ready`, run **that one file** with `VEYQ_E2E=1`, tear the boot down → aggregate exit: report
      every failing file, exit non-zero if **any** failed. Never short-circuit (the skeleton's one genuinely
      reusable idea). Log what it is doing at each step; a silent orchestrator is unreviewable in CI.
      Two shape constraints, not later refactors: **discovery is its own importable function with a fail-on-empty
      contract** — it raises on an empty match set instead of returning `[]` — because Phase 4's permanent guard
      must reach exactly that behaviour without Docker and without a built jar; and `VEYQ_GEOCODER` is left
      **unset**, with the comment carrying trap 4's two reasons.
      · Done when: `python3 scripts/run_web_e2e.py` green locally from a clean `docker compose down -v`, and
      green **twice in a row** without a manual reset in between.

- [x] **4 · bite test — and the one part of it that becomes permanent.** Four injections, each of which must exit
      non-zero *and* say which one happened: (a) store absent — stop the garage container; (b) backend never
      becomes ready — point a boot at a dead port; (c) a suite fails — invert one assertion; (d) **zero e2e files
      matched** — point discovery at a directory with none. (d) is the load-bearing one: an orchestrator that
      reports green because it ran nothing is the exact false negative this whole item exists to remove
      ([`changes/README.md`](README.md) § a bite test must tell "the guard held" apart from "nothing ran").
      Injections use snapshot-and-restore (`cp` → break → run → `command cp -f` → confirm the marker), **never**
      `git checkout --`.

      **(d) does not stay manual — it is the one injection decidable without Docker and without a jar, so it
      becomes a `scripts/validate_*.py` guard** ([`changes/README.md`](README.md) § promote one-shot proofs). It
      imports the orchestrator's discovery function and asserts both halves: that it finds ≥1 suite in the real
      tree (so the guard cannot pass by pointing at nothing), and that an empty match set **raises** rather than
      returning green — the latter over a throwaway tree copy, the repeated-injection form
      `validate_guard_gaps.py` already uses, so an abort never leaves the live tree dirty. Docstring carries the
      three required fields ([`changes/README.md`](README.md) § trust-chain guards), and its *limit* field states
      plainly what it does not reach: that the boot, the store and the assertions ran at all — that stays with the
      CI job. Commit the guard before believing a local green; the glob reads the committed tree.
      · Done when: (a)–(c) recorded in this CR with their observed exit code and identifying message, and the (d)
      guard committed, red on the injected empty tree, green after — `for f in scripts/validate_*.py; do
      python3 "$f"; done` green with it in the glob.

      **Observed, 2026-07-30 at `0e2095d4`.** Every injection exited **1** and named its own cause:

      | Injection | How | Reported |
      |---|---|---|
      | a1 · store unreachable | `VEYQ_BLOB_STORE_ENDPOINT=http://127.0.0.1:19999` | `media.e2e.test.ts: the suite failed (vitest exit 1)` — the PUT, exactly where trap 2 says it surfaces |
      | a2 · store absent | `VEYQ_BLOB_STORE_ENDPOINT=` (blank) | `the JVM exited with code 1 before becoming ready`, and the tailed boot log carries `veyq.blob-store.endpoint is required outside the %test profile` — `BlobStoreStartupCheck` firing |
      | b · never ready | `VEYQ_BOOT_SECS=1` | `nothing answered the readiness probe http://127.0.0.1:8080/api/setup within 1s` |
      | c · a suite fails | one assertion in `memoryCover.e2e.test.ts` inverted to `.not.toEqual(cover)` | `the suite failed (vitest exit 1)`, with vitest's `expected { …(2) } to not deeply equal { …(2) }` above it |
      | d · zero files | now `validate_e2e_discovery.py`, bitten three ways | `discover() on an empty tree returned [] instead of raising` / `does not re-raise as SystemExit — the error is swallowed` / `does not read \`else discover()\`` |

      (c) used snapshot-and-restore and the restore was confirmed by re-running the file green, not by grep alone.
      Two notes for whoever repeats this. **The literal "(a) stop the garage container" injection does not work**
      against this orchestrator: its own `compose up -d` restarts a stopped container, which is correct behaviour
      and makes the container the wrong lever — drive the endpoint instead, as a1/a2 do. And (a2)+(b) are worth
      keeping distinct even though both read as "not ready": they exercise the **JVM-died** and the **timeout**
      branches, and it was the *absence* of that distinction that made the first real run unreadable.

- [ ] **5 · CI + docs** — Drop `if: false` from the `web-e2e` job and replace its disabled-comment block with
      what the job now does. Correct the *"fresh in-mem DB per file"* claim in the fast-jar step's comment (F2
      disproved it). Update `architecture/README.md` §11: the row closes, and its link into `changes/` goes away
      with it (this CR is deleted at close — a dangling pointer here is what `validate_doc_links.py` would catch,
      and only if the link is a markdown link, which it is).
      · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green, and the branch pushed so the
      real `web-e2e` job runs.

- [ ] **6 · coverage — only after a green CI run** — Restore the four refs removed by `52bca34b`. Their home
      **moved** since that commit: the use-case specs are now `.md` under `docs/features/`, and coverage refs
      live in a separate tree under `docs/coverage/features/`. So:
      `docs/coverage/features/memory/use-cases/uc.memory.cover.yaml` → `memoryCover.e2e.test.ts`, and
      `docs/coverage/features/spaces/use-cases/uc.spaces.{add-group-member,manage-group-members,remove-tenant-member}.yaml`
      → `scopeRotation.e2e.test.ts` (in `remove-tenant-member.yaml` it goes beside the existing
      `web: { unit: [...scopeRotationWorker.test.ts] }` on the `coshared-group-rotation` criterion, whose prose
      comment already asserts the live verification). `media.e2e.test.ts` and `memoryLocation.e2e.test.ts` never
      carried refs — adding them would be a **new** claim and is out of scope here; if they should, that is its
      own judgement in its own commit.
      · Done when: the CI run is green **and** `python3 scripts/validate_coverage.py` green. This phase must not
      land before that run exists — it is the obligation ADR 0040's Correction exists to stop us botching twice.

## Definition of done

`cd backend && ./gradlew check` and `cd web && npm run check` green; the full trust chain green
(`for f in scripts/validate_*.py; do python3 "$f"; done`); `python3 scripts/run_web_e2e.py` green locally and
**the `web-e2e` job green in CI**; injections (a)–(c) recorded with their observed output and the (d) guard
committed and green in the glob; the four coverage refs restored with `python3 scripts/validate_coverage.py` green.

The structural invariant this CR rests on — *the orchestrator cannot report green without running the files* —
**splits in two, and only one half is cheap to guard.** "It ran nothing" is decidable from the discovery function
alone, needs neither Docker nor a jar, and therefore becomes a permanent `validate_*` guard (Phase 4d): the failure
mode that this whole item exists to remove is the one that gets durable enforcement. "It ran them, but the boot,
the store or the assertions were hollow" is **not** cheaply decidable — that check needs the full stack, so it
cannot join the buildless glob, and its enforcement is the `web-e2e` job itself: a red job is the guard. The
aggregate-exit design (Phase 3) and injections (a)–(c) are that half's one-shot evidence, recorded here and
knowingly manual. If a cheap automatable form of it appears later, it belongs in `scripts/`.

## Retrospective

Written at close — planning, execution, **and** process/collaboration (were the conventions we already agreed
honored: language, the workflow rules, the memory notes?), both directions, ending in one concrete "next time".
