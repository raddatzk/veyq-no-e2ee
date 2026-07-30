# Scoping — the web e2e orchestrator (`scripts/run_web_e2e.py`)

> **Status: grounding / scoping only. Not a CR, nothing built.** Pre-work that becomes `cr-e2e-orchestrator`
> once the decisions below are made and a "go" is given. Deliberately not named `cr-*.md` so
> `scripts/validate_changes.py` ignores it (same convention as `scoping-device-linking.md`).
>
> **Substrate for every measurement here:** branch `starting-something-new` at `a8bbfc83`, working tree clean
> except an untracked `harness/` (`git status --short`). All `path:line` facts are **measured**; anything
> derived is marked `[inferred]`. **[DECISION]** marks a call the owner must make.

## Why this one (the case is already made, elsewhere)

[`architecture/README.md:264`](../docs/architecture/README.md) — *"Open, and the **highest-value item**
here"*. Its stated reason: these tests catch what unit tests structurally cannot, and one of them once caught
a security-relevant key-zeroing bug that every unit test passed. Today they run only when a human remembers.

Two documents make it load-bearing rather than merely nice:

- [`docs/ai/README.md:204-209`](../docs/ai/README.md) — the four coverage refs those suites carried were
  **removed** because a test no CI run executes makes no coverage claim, and: *"the day it is green, the refs
  come back"*. So the rewrite has a **coverage follow-through obligation**, and shipping it is the only thing
  that discharges it.
- [`docs/coverage/README.md:36`](../docs/coverage/README.md) — *"a web suite run against a live backend
  (`run_web_e2e.py`) is **e2e**"*. The script's name is baked into the coverage taxonomy: today the taxonomy
  names a file that does not exist.
- [`inbox.md` #102](../docs/inbox.md) — the wire assertion (*is the ciphertext actually ciphertext?*) is
  explicitly **blocked on this debt**. It is the named downstream consumer, and its oracle — the
  server-observable bytes — only exists once a real flow runs against a real backend and a real store.

And a warning from the same file: [ADR 0040 §Correction (2026-07-14)](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)
records that the coverage table once claimed `run_web_e2e.py` ran when it did not. **This claim has already
been false once.** Whatever we build, the green must be observable in CI, not asserted in prose.

## 1. The interface is already fully specified — by the dead job

[`ci.yml:72-109`](../.github/workflows/ci.yml) **is** the spec, and it is tighter than it looks:

| Fact (measured) | Consequence for the rewrite |
|---|---|
| `:109` `run: python3 scripts/run_web_e2e.py` | No args, invoked **from the repo root**. |
| No `env:`, no `with:`, no `services:` on the job | The orchestrator supplies **DB + object store itself**. Nothing is handed to it. |
| No `pip install` step anywhere in the job | **Python stdlib only.** (`trust-chain.yml:48-50` installs `pyyaml` — that is a *different* job.) |
| `:82-96` Java 21 + Gradle + Node 22 (`cache: npm`) | Already provisioned; the script must not re-provision. |
| `:100-102` `./gradlew --no-daemon quarkusBuild` (working-dir `backend`) | The **fast-jar is built once, before** the script runs. |
| `:104-106` `npm ci` in `web` | Frontend deps are ready. |
| `:98-99` comment: *"Build the runnable fast-jar once; the orchestrator boots a fresh copy (fresh in-mem DB) **per e2e file**, because every suite registers its own single-owner-per-boot Server Owner."* | The load-bearing design line: **one build, N boots, one per file.** |
| `trust-chain.yml:51` globs `scripts/validate_*.py` | `run_web_e2e.py` is **outside** that glob — correct (ci.yml calls it directly), but it gets no free CI coverage of its own. |

Nothing here needs deciding. It is the contract the rewrite must satisfy.

## 2. Correction: the orchestrator is **not** unrecoverable — and it died next to a second artifact

Both [`ci.yml:74`](../.github/workflows/ci.yml) and [`architecture/README.md:264`](../docs/architecture/README.md)
assert the file *"was lost in the greenfield reset"* and *"cannot be restored — it must be rewritten"*.
**Measured, the recoverability half is false:**

```
git log --all --format='%h %ad %s' --date=short -- scripts/run_web_e2e.py
#   5432372d 2026-06-25 cleanup            (D)
#   a838b794 2026-06-16 ci(web): run E2E suites against a live backend per file (P5.2)   (A)
git cat-file -s a838b794:scripts/run_web_e2e.py     # → 4504
git merge-base --is-ancestor a838b794 HEAD          # → true
```

4504 bytes, intact, on an **ancestor of HEAD**. It was not lost to a reset — it was deleted by a commit
titled `cleanup` whose diff is overwhelmingly `.derivedData/` build junk. The same commit also deleted the
**root `Dockerfile`** (`git log --all --diff-filter=AD --name-status -- Dockerfile` → added `6cd71117`,
deleted `5432372d`), which [`container.yml:201-203`](../.github/workflows/container.yml) still builds
(`context: .`, `file: Dockerfile`, `target: runtime-jvm`). That job is **not** `if: false`; it only never
fired because it triggers on `main`/`release/**`/tags/PRs-to-`main` (`container.yml:3-25`) and this branch has
never gone there. **It will fail on the first PR to `main`.** One untargeted cleanup took out two
CI-referenced artifacts; one was noticed and honestly disabled, the other was not. See §8.

**The docs' conclusion still holds — its reason does not.** A literal restore will not run; four measured
reasons:

1. `FRONTEND = REPO / "frontend"` and it globs `frontend/src/**/*.e2e.test.ts` — the tree is `web/`.
2. It passes `VEYQ_API_BASE`; today's suites read `VEYQ_BASE` (`grep -rn VEYQ_API_BASE web/` → none).
3. It sets `VEYQ_RATELIMIT_ENABLED=false` and `VEYQ_MAIL_MOCK=true` — **neither knob has ever existed** in
   this backend, and HEAD's `a7657b20` specifically closed the disable-the-limiter-by-absurd-window trick.
4. Its whole premise — *"one fresh backend per file because `jdbc:h2:mem` only resets when the JVM restarts"* —
   is a `%test`-profile fact, and it does not address the tenant split in §3.

**What to do with it is itself a decision ([DECISION A]).** Note the constraint from
[`docs/ai/README.md`](../docs/ai/README.md) rule 5 — *don't offload meaning to git; treat history as
expendable*. Citing a commit hash for recoverability is exactly the pattern that rule forbids. So the honest
options are: pull the skeleton into the tree **now** as the starting point, or knowingly let it go. Leaving a
hash in a doc is the one option the rules exclude.

## 3. Current state — what actually has to run (measured)

Four suites, all Vitest (**no Playwright** — no `playwright.config.*`, no dep in `web/package.json`), all
node-runtime, all gated identically by `describe.skipIf(!process.env.VEYQ_E2E)`:

| Suite | Drives | Needs |
|---|---|---|
| [`media.e2e.test.ts:38`](../web/src/features/memory/media.e2e.test.ts) | login → unlock → createMemory → addMedia/listMedia/openOriginal; 300 KiB through **real presigned PUT/GET** (incl. CORS) | backend + **real S3 store** + seeded account |
| [`memoryCover.e2e.test.ts:34`](../web/src/features/memory/memoryCover.e2e.test.ts) | successive real `updateNode`: cover+date+location in one `wrappedMeta`, read-modify-write never clobbers siblings | backend + seeded account |
| [`memoryLocation.e2e.test.ts:40`](../web/src/features/memory/memoryLocation.e2e.test.ts) | sealed location round-trip; asserts the server-echoed ciphertext holds no plaintext name/lat/date; optional real reverse-geocode via `VEYQ_GEOCODER` (`:60`) | backend + seeded account |
| [`scopeRotation.e2e.test.ts:63`](../web/src/features/tasks/scopeRotation.e2e.test.ts) | cr-0092/0093: 4 actors, cold-birth server owner + tenant, grant delivery, member removal → queued `SCOPE_ROTATION`, only the remaining key-holder can claim it, epoch 0→1, chain-link, late-member read. Real Argon2id ×N ⇒ `240_000` ms timeout (`:158`) | **fresh instance**, no seeder |

`web/package.json` has **no `test:e2e` script**, and `web/vite.config.ts:122-125` sets only `setupFiles` — no
`include`/`exclude`/`projects`. The e2e files therefore match Vitest's default glob and **ride the normal
`npm run check`**, skipping at zero cost. So the orchestrator's job is purely: bring up the world, set
`VEYQ_E2E=1`, and run **one file at a time**.

## 4. The five forks — this is the whole design problem

### F1 — The store is mandatory, and it cannot be a GH Actions service container

[`BlobStoreStartupCheck.kt:22-33`](../backend/src/main/kotlin/com/veyq/app/shared/blobstore/BlobStoreStartupCheck.kt):
`hasStore = mode == LaunchMode.TEST || endpoint.isNotBlank()`, wrapped in a `check()` on `StartupEvent`.
Outside `%test` a blank endpoint **aborts startup**; the docstring is explicit that media *"is not an optional
feature, so an instance without a store is misconfigured, not gracefully degraded"*. **There is no
`in-memory` escape hatch for a booted jar.**

Garage cannot be declared as `services:` — measured blockers: it needs `/etc/garage.toml`, which compose
bind-mounts from the repo (`infra/docker-compose.yml:39`) and service containers start *before* checkout
`[inferred, remembered Actions semantics]`; the official image is **shell-less scratch**, which is precisely
why `infra/garage-init.Dockerfile:1-3` exists; bootstrap is a mandatory 5-step sequence (`layout assign` →
`layout apply` → `bucket create` → `key import` → `bucket allow`, `infra/garage-init.sh:38-48`); and the init
sidecar is **built locally** from the repo (`docker-compose.yml:59-62`).

→ The orchestrator must shell out to `docker compose -f infra/docker-compose.yml --profile s3 up -d`, reusing
the two-step wait idiom from `.run/Bootstrap DB + Garage.run.xml:7`
(a single `--wait` on the whole project exits 1, because `garage-init` exits even on success and `--wait`
scores any exit as failure). `garage-init.sh:2-4,33-36` is idempotent and self-heals after `down -v`.

### F2 — A fast-jar runs `%prod`, and `%prod` is hostile to a test run

Every relaxation the suites depend on lives in `%dev`/`%test` only:

| Setting | `%prod` (the jar) | relaxed in |
|---|---|---|
| datasource URL/user/pass | **none** — env-only (`application-prod.properties:1-3`) | `%dev` hardcodes MariaDB `:5-7` |
| `veyq.blob-store.endpoint` | none ⇒ startup aborts (F1) | `%dev` `:18` → `http://localhost:19000` |
| `veyq.session.cookie.secure` | `true` (`application.properties:65`) | `false` in `%dev`/`%test` — plain-http "remember me" breaks without it |
| `veyq.ratelimit.auth.max-requests` | `60`/60 s (`:75-76`) on login challenge/verify, refresh, account creation | `%test` raises to `1_000_000` |

A 4-actor suite doing real Argon2id logins against a 60/min IP budget is a coin flip. **[DECISION B]:** boot
the fast-jar with a pile of env overrides (`QUARKUS_DATASOURCE_*`, `VEYQ_BLOB_STORE_*`, cookie, four
rate-limit budgets), or boot it with `-Dquarkus.profile=dev` and inherit the relaxations wholesale, or add a
dedicated named profile (`%e2e`) to `application-*.properties` so the relaxations are **declared in the repo
instead of assembled in a script**. Recommendation: the named `%e2e` profile — it is the one option where a
reader can see what the e2e run relaxes, and the script stops carrying policy.

**And the "fresh in-mem DB per file" in `ci.yml:98-99` does not work as written — measured 2026-07-30, not
inferred.** An earlier draft of this doc reasoned that a `%prod` jar can run H2 because `quarkus-jdbc-h2` is
`implementation`, not `testImplementation` (`backend/build.gradle.kts:35`). Both driver jars do ship
(`ls /work/quarkus-app/lib/main` in the recovered container image → `com.h2database.h2-2.4.240.jar` **and**
`org.mariadb.jdbc.mariadb-java-client-3.5.8.jar`), but running that image with
`QUARKUS_DATASOURCE_DB_KIND=h2` + a `jdbc:h2:mem:` URL fails at startup:

```
Caused by: java.sql.SQLException: Driver does not support the provided URL: jdbc:h2:mem:smoke;MODE=MariaDB
```

`quarkus.datasource.db-kind` is a **build-time** property — the MariaDB driver was baked in at augmentation
(`application-prod.properties:1-3`) and the runtime override is ignored, so the *MariaDB* driver is what
rejects the H2 URL. Consequences for **[DECISION B]**: either build a second, H2-configured jar (which breaks
`ci.yml`'s "build once"), or give each boot a real MariaDB. The cheap third option the measurement opens up:
**one** MariaDB container plus a fresh `CREATE DATABASE veyq_e2e_<n>` per e2e file — a fresh *instance* in the
only sense the suites care about (`setupRequired === true`), at one container and no rebuild.

### F3 — Two incompatible bootstrap contracts, and one of them pokes the DB

- `scopeRotation.e2e.test.ts:66-67` **asserts** `setupRequired === true` (*"run against a FRESH instance"*) and
  self-bootstraps everything: `createServerOwner` → `createTenant(…, HOST, …)` → `acceptInvite(tenant.bootstrapSecret)`
  → 2× `inviteMember().bootstrapSecret` → `registerAndAccept`. Host default `veyq.localhost` (`:38`).
- The three memory suites read `web/scripts/.seed/dev-seed.json`, keyed `seed.tenants['127.0.0.1']`, produced
  by `npm run seed:dev`. And `seed-dev.mts:55-58` reaches for
  **`docker exec veyq-mariadb mariadb … UPDATE veyq_tenant SET domain='127.0.0.1'`** — because
  `POST /api/tenants` rejects a numeric domain (`:19-23`).

That `docker exec` is fatal to the CI design: with an in-JVM H2 there is no `veyq-mariadb` container to poke.
The two host conventions are also documented as **not interchangeable** — wrong host/creds surfaces as a
`401` at `POST /api/login/verify`, not as a crypto bug ([`web/CLAUDE.md:42-52`](../web/CLAUDE.md)).

**[DECISION C]:** converge the three memory suites onto the self-bootstrapping contract (they gain
`createServerOwner` + `createTenant` + accept, ~15 lines each, reusing the helpers `scopeRotation` already
proves), **or** run MariaDB as a container in CI and keep the seeder + the `docker exec` domain rewrite.
Recommendation: **converge**. It deletes the numeric-domain hack, deletes the DB poke, makes every file
self-sufficient — which is what the per-file fresh boot in `ci.yml:98-99` already assumes — and lets one host
convention (`veyq.localhost`, valid dotted domain *and* RFC 6761 loopback, per `scopeRotation.e2e.test.ts:35-38`)
serve every suite. Cost: it edits the suites, so the CR touches web, not only `scripts/`.

### F4 — The dev-invite file does not exist for a booted jar

[`TenantInviteMailer.kt:59-67`](../backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantInviteMailer.kt):
in `LaunchMode.NORMAL` the mailer **early-returns before** the dev-file append; the docstring states the
append sits after the production early-return *"so a `LaunchMode.NORMAL` runtime never writes the secret"*. A
`quarkus-run.jar` boot is `NORMAL` `[inferred, high confidence]`, so `VEYQ_TENANT_INVITE_DEV_FILE` and
`awaitInvite()` (`web/scripts/devSeed.mts:101-116`) are unusable in CI.

Not a problem: `bootstrapSecret` is `required` on the HTTP response
(`contracts/api/tenants.openapi.yaml:1088`, and `:1152`/`:1254` for member invites) — which is exactly the
path `scopeRotation` already takes. Worth naming because inbox #13's follow-up (1) weighed making it optional
and settled **won't-do**; that decision is what keeps this path open. If it is ever reopened, this breaks.

### F5 — `fetch` strips a manual `Host`, and tenants resolve by Host

Tenant resolution is server-side from the request `Host`, port stripped (`web/CLAUDE.md:48-52`). And
`web/scripts/bootstrap-user.mts:55-60` uses raw `node:http` instead of `fetch` **specifically because
fetch/undici strips a manually set `Host` header**. → the base URL must carry the tenant domain for real
(`http://veyq.localhost:8080`), not a spoofed header. `scopeRotation` already does this; converging on it
(F3) makes the problem disappear rather than needing a workaround.

## 5. What does "green" mean — scope of the rewrite

The pre-reset tree carried **~30** `*.e2e.test.ts` (login, registerViaInvitation, resetPassword, tenant,
inviteMembers/changeRole/removeMember/rotateScope, setupServer, contentSurvivesRotation, browse, journal,
share, track, …) plus a Playwright-style `frontend/e2e/core-flow.spec.ts` and sibling `*E2ETest.kt` /
`*E2ETests.swift`, all deleted (`git log --all --diff-filter=D --name-only | grep -i e2e`). Today's four are
a small fraction. **[DECISION D]:** re-green the four that exist (the orchestrator *is* the deliverable), or
treat this as "restore e2e breadth". Recommendation: the four. Breadth is a separate, later CR — and it gets
cheap once the harness runs, which is the whole argument for doing the harness first.

## 6. Definition of done — what the CR must carry

- `scripts/run_web_e2e.py` exists, stdlib-only, no args, run from the repo root; brings up compose
  (`--profile s3`), boots one backend per e2e file, waits on `/q/health/ready`, runs that one file with
  `VEYQ_E2E=1`, tears down, and exits non-zero if **any** file failed (aggregate, don't short-circuit — the
  recovered skeleton's one genuinely reusable idea).
- `if: false` removed from `ci.yml:76`, and the `:74` comment replaced by the truth (§2).
- `architecture/README.md:264` row updated — closed, or narrowed to what remains.
- **The four coverage refs come back** (`docs/ai/README.md:204-209`) — and *only* on a green CI run, never
  before. This is the obligation ADR 0040's Correction exists to prevent us from botching twice.
- A **bite test**: the orchestrator must fail loudly when the store is absent, the backend never becomes
  ready, or a suite fails — *"a bite test must tell 'the guard held' apart from 'nothing ran'"*
  ([`changes/README.md`](README.md)). An orchestrator that reports green because it ran zero files is the
  exact false negative this whole item exists to remove.

## 7. Then #102 becomes buildable

With a real backend + real store under a script, the wire assertion has its oracle: assert the
**server-observable bytes** are ciphertext plus opaque metadata and nothing else. That is the check no agent
can satisfy by weakening an assertion, and it is the last unmeasured link in the operator-blind claim. It
stays its own CR — but it stops being blocked.

## 8. Decisions the owner must make

- **[DECISION A] The recovered skeleton** — pull `a838b794:scripts/run_web_e2e.py` into the tree as the
  starting point (boot → health-poll → one file → teardown → aggregate-exit is directly reusable), or write
  fresh and let it go. Not "leave the hash in a doc" — `docs/ai/README.md` rule 5 excludes that.
- **[DECISION B] How the booted jar gets test-shaped config** — env-override pile vs `-Dquarkus.profile=dev`
  vs a declared `%e2e` profile (recommended: `%e2e`).
- **[DECISION C] One bootstrap contract or two** — converge the three memory suites onto self-bootstrapping
  (recommended; deletes the `docker exec` DB poke and the numeric-domain hack, but edits web) vs keep the
  seeder and run MariaDB in CI.
- **[DECISION D] Scope** — re-green the four surviving suites (recommended) vs restore e2e breadth.
- **[DECISION E] The root `Dockerfile` (§2)** — the same `cleanup` commit deleted it, `container.yml:201-203`
  still builds it, and that job is not disabled: it will fail on the first PR to `main`. Recoverable via
  `git show 5432372d^:Dockerfile`. Fold into this CR (both artifacts died together), spin off as its own, or
  disable the job honestly like `web-e2e` was. **This one has a deadline the others don't.**

## 9. Stale in-repo comments a rewriter will trip over (fix regardless of the decisions)

Both contradict `BlobStoreStartupCheck` and would send an implementer down a fallback path that no longer
exists:

- `.run/Run Backend.run.xml:17-20` — *"Without an endpoint the backend falls
  back to the in-memory dev store (canPresign=false) → … store_unavailable"*. It does not; it aborts startup.
- [`infra/README.md:45-46`](../infra/README.md) — *"A blank `endpoint` keeps the in-memory dev store …
  `/api/setup` then reports `directTransport: false`"*. `BlobStoreStartupCheck.kt:12-14` states there is no
  client-facing `directTransport` flag to soft-gate on any more.

## Measured vs. inferred

- **Measured:** the whole `web-e2e` job body (`ci.yml:72-109`) incl. the no-pip/no-services/no-env shape; the
  four e2e suites, their `skipIf` gates and env reads; `vite.config.ts:122-125` and the absent `test:e2e`
  script; the git archaeology for both `scripts/run_web_e2e.py` and `Dockerfile` (add/delete commits, 4504
  bytes, ancestor-of-HEAD) and the four staleness points in the recovered file; `BlobStoreStartupCheck.kt`
  (`hasStore`, the abort, the docstring); the `%prod`/`%dev`/`%test` config deltas (datasource, endpoint,
  cookie, rate limits) and `build.gradle.kts:35`; `TenantInviteMailer.kt:59-67`; `bootstrapSecret` being
  `required` in `tenants.openapi.yaml:1088`; the compose services/profiles/ports/creds and Garage's
  scratch-image + 5-step init; `seed-dev.mts:55-58`'s `docker exec`; the Host/fetch note in
  `bootstrap-user.mts:55-60`; the absence of any `.http` file and of any process-orchestrating Python in
  `scripts/`; `trust-chain.yml:51`'s glob; `architecture/README.md:264`, `ai/README.md:204-209`,
  `coverage/README.md:36`, ADR 0040's Correction, and `container.yml:3-25,201-203`.
- **Promoted from inferred to measured (2026-07-30, via the recovered `Dockerfile` — see §8 [DECISION E]):**
  that a `%prod` jar can run H2 — it **cannot**; `db-kind` is baked at augmentation. The container build that
  proved it also measured that the single-image model still holds: the web `dist` lands in the app jar
  (`META-INF/resources/index.html` + `sw.js` + 18 hashed assets).
- **Inferred:** that a `quarkus-run.jar` boot is `LaunchMode.NORMAL` (from the mailer's own docstring naming
  that mode as production); that GH Actions service containers cannot serve Garage (from the bind-mount +
  scratch-image + local-build facts plus remembered Actions start-ordering); that `docker`/`docker compose`
  are present on `ubuntu-latest`; that converging F3 costs ~15 lines per suite (from reading `scopeRotation`'s
  bootstrap block, not from writing it).
