---
id: cr-0165
title: The share-password brute-force bound is real — per-share failed-attempt limit (ADR 0037 corrected)
status: done
satisfies: ["uc.sharing.share-password"]
adrs: ["0037"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the brute-force bound that [ADR 0037](../docs/decisions/0037-password-protected-shares.md) claims and the
code does not have — and **correct the ADR's claim while doing it**, because the shape it names is the wrong one.

ADR 0037 argues in three steps: (1) a link-holder cannot brute-force **offline** — `pw_verifier` is never served;
(2) so they must guess **online**, against the server; (3) "*online, **rate-limited per IP***" — therefore bounded.
**Step 3 does not exist.** `resolveShare` carries `@PermitAll` and no `@RateLimited`; the annotation governs
login, token-refresh, setup and two tenant-invite ops only. An attacker holding the link fetches `pw_salt` from
the locked resolve (open by design — the client needs it), computes `Argon2id(candidate, salt)` locally, and
posts the token as often as they like.

**Why this is not merely a missing belt-and-braces:** the server **never sees the password** (ADR 0037's own
operator-blind design — the client derives `pwToken`, the server stores only `SHA-256(pwToken)`), so it
**structurally cannot enforce password strength**. The 10-character minimum lives only in the web client's
`ShareCreateDialog`; the contract does not carry it and no server check can. A hand-written client — or a future
native one that simply forgets — sets a 4-character password, and Argon2id alone is then a short bound. **The
server-side attempt limit is therefore not redundant with the minimum length; it is the only bound the blind
server can itself guarantee.**

Found during the 2026-07-14 docs-vs-implementation review. Sibling: [cr-0164](cr-0164-share-password-write-path-gate.md)
(same feature, **different defect class** — that one fixes a missing *gate*, this one a missing *bound*).
**Lands after cr-0164**, whose uniform check gives this CR its single counting site (decision 3).

## Context & constraints

- **This CR changes a decision.** ADR 0037 says *per-IP*; decision 1 replaces that with *per-share failed
  attempts*. That is a correction of the recorded choice, not an implementation detail — hence the ADR edit is
  phase 1, in place, no addendum (`docs/README.md` § current-state).
- **`@RateLimited` is the wrong instrument here, and its own KDoc says why.** `AuthRateLimitFilter` keys on
  `request.remoteAddress()` and **deliberately does not trust `X-Forwarded-For`** ("*We intentionally do NOT
  trust forwarded headers by default*"). Behind the Traefik in `infra/`, every guest of a share is therefore
  **one IP** — a 60-per-60s bound would become a global cap on the whole share, throttling legitimate visitors.
- **`cc.security.rate-limit.md` is cited by ADR 0037 as the bound and does not claim `sharing` at all** — it
  scopes itself to the auth surface. Adding a second mechanism means editing it; the review found it carries
  three stale claims which are corrected in the same pass (see `## Found in passing`).
- **No contract change** — the 429 and its `ErrorResponse` projection already exist
  (`ShareUploadOutcome.RateLimited` → `Failures.rateLimited`, [ADR 0042](../docs/decisions/0042-http-failure-projection.md)).
  So: backend + docs only, and the live smoke rides the `quarkusDev` hot-reload.
- **No crypto, no key path, no migration.**

## Decisions

1. **The bound is per-share failed attempts, not per-IP.** A password attack targets **one** share; the counter
   belongs on that axis. It bites the attack exactly and **never touches a legitimate guest**, who knows the
   password and does not fail.
   *Rejected — `@RateLimited` on `resolveShare` (what ADR 0037 literally says).* Two independent defects: behind a
   reverse proxy it degrades to a global per-share cap (above), and it counts **successful** resolves as well, so
   a popular share throttles its own readers. The ADR named the mechanism without checking what the mechanism keys on.

2. **Only `WRONG` counts. `MISSING` must not.** A tokenless resolve is the **normal first call** of every
   legitimate guest — it is how the client fetches the challenge (`pw_salt`). Counting `MISSING` would have every
   honest visitor spend the budget by opening the page. This is the one detail that decides whether the feature is
   usable; it is called out here because it is invisible in the "add a rate limit" framing.

3. **The counter sits inside `passwordCheck`.** It is the single place that produces `WRONG`, and after cr-0164 it
   has **six** call sites — repeating a guard at six sites is the shape cr-0160/cr-0161 spent two CRs collapsing.
   `passwordCheck` stops being pure; that is the trade, and it is the cheaper one.

4. **A second limiter instance, not an abstraction.** `ShareRateLimiter` already implements exactly this
   (per-`shareId`, fixed window, `allow(key): Boolean`) for uploads. Password attempts need their **own budget**
   (an order of magnitude tighter than an upload burst), so: a sibling bean with its own config keys, mirroring
   the existing one. Two usages do not justify generalising a 34-line class — per the refinement checklist, the
   abstraction waits for the third.

5. **The client-side minimum length is documented as what it is.** ADR 0037 gains an honest statement: password
   strength is enforced **client-side only** and the blind server cannot verify it — which is precisely why the
   attempt bound exists. Today the 10-char minimum is undocumented in any doc or contract (review finding).

## Inventory

Counts are command output at `b9cdf17b`; symbols are greppable, line numbers omitted (they drift).

| Site | Today | After |
|---|---|---|
| `docs/decisions/0037-…md` — the bound claim (`grep -c 'rate-limited' …0037*.md` → **1**; a second, separate hit is the `cc.security.rate-limit` link at the Refs line) | "online, rate-limited per IP" | per-share failed-attempt bound + the client-side-strength honesty (decision 5) |
| `docs/features/sharing/use-cases/uc.sharing.share-password.md` (`grep -c 'rate-limited'` → **1**) | "Online guessing is per-IP rate-limited" | per-share failed-attempt wording |
| `docs/crosscutting/cc.security.rate-limit.md` | claims the auth surface only | + the sharing mechanism; 3 stale claims fixed (below) |
| `ShareRateLimiter` | one bean, per-`shareId`, upload budget | + a sibling password-attempt bean, own config keys |
| `SharingHandler.passwordCheck` | pure; returns `OK/MISSING/WRONG` | counts on `WRONG` only (decision 2); returns a 4th `TOO_MANY` |
| `SharingHandler.resolve` / `resolveBlob` (+ the 4 write sites cr-0164 adds) | 2 call sites | **6** — all inherit the bound for free (decision 3) |
| `Failures.rateLimited` → 429 | exists, used by `ShareUploadOutcome.RateLimited` | reused verbatim — no contract change |

**Test blast radius — expected zero flips.** The test profile defuses the auth limiter by raising its budget —
`backend/src/main/resources/application-test.properties:12` → `veyq.ratelimit.auth.max-requests=1000000` (note
the file lives under **main**/resources; there is no `backend/src/test/resources/`). The new bean needs the same
deliberate default, or a password test asserting a wrong-token 403 could start seeing 429.
`SharePasswordSliceTest` has **5** `@Test` methods (`grep -c '@Test' …SharePasswordSliceTest.kt` → 5), of which
exactly one — `validTokenUnlocksAndWrongTokenIs403` — presents a wrong token, and only once. Any budget N>1
leaves it green. **Set the suite default deliberately; don't discover it.**

**The bound's own test inverts this, and the pattern already exists:** `AuthRateLimitTest` drives the limit
*down* via `@TestProfile` + `getConfigOverrides()` (`max-requests` → `3`) instead of touching the global test
config. `SharePasswordAttemptBoundTest` follows that shape — a tight per-test budget, suite default untouched.

## Found in passing — corrected because the file is edited anyway

`cc.security.rate-limit.md` carries three claims the review disproved. They are fixed in phase 1 (the same file
the sharing mechanism is added to); each is a one-line correction, not separate work:

1. "**Two** public-auth resources… the authoritative list is `auth.openapi.yaml` plus the annotation on those two"
   → it is **5 endpoints across 4 resources in 3 specs** (`grep -rn '@RateLimited' backend/src/main/kotlin` → 6
   hits: 2 class-level + 3 method-level + the filter itself), and `auth.openapi.yaml` cannot express the set
   because `setup`/`tenants` carry governed ops.
2. "**no separate static 'is the annotation present?' check is needed**… revisit then (pain before tool)" → that
   check **exists**: `shared/ratelimit/RateLimitedEndpointsTest.kt`, added by cr-0130.
3. Status "verification **complete** — every governed endpoint asserted" → the 3 method-level endpoints have **no**
   runtime 429 test; only the static annotation check covers them.

The irony is worth one line in the retro: this is the document that warns against hand-kept matrices drifting,
citing cr-0044's phantom `/api/token` row — and it drifted.

## Plan

Single-phase-per-seam; backend + docs, no contract change.

1. **docs** — ADR 0037 (decisions 1 + 5, in place), the UC's bound wording, `cc.security.rate-limit.md`
   (the sharing mechanism + the 3 corrections). Done when: `validate_doc_links.py` green.
2. **backend** — the sibling limiter bean + config keys (+ the `%test` budget, deliberately), `passwordCheck`
   counting `WRONG` and returning `TOO_MANY` → `Failures.rateLimited`. Done when: `./gradlew check` green **with
   no existing assertion changed**.
3. **the guard + coverage** — `SharePasswordAttemptBoundTest` + a coverage ref on the UC's bound criterion.
   Done when: it reds on a removed counter and `validate_coverage.py` green.

## Verify

- `./gradlew cleanTest check` in `backend/` — green, **no existing assertion touched**.
- **Enforcement guard (permanent, carries the invariant):** `SharePasswordAttemptBoundTest` asserting, over a
  password-protected share: (a) **N wrong tokens are refused 403, the N+1st is 429** — the bound bites;
  (b) **a tokenless (`MISSING`) resolve never consumes budget** — fire it 3×N times, then a wrong token still
  answers 403, not 429 (this pins decision 2, the one that would silently break honest guests);
  (c) **a valid token is unaffected** by another share's exhausted budget — pins the per-share axis;
  (d) after cr-0164, one write op inherits the bound — pins decision 3.
  **Verified to bite:** removing the counter must red (a).
- Live smoke on `veyq.localhost:8081` (no contract change → hot-reload is fine): repeated wrong passwords on a
  share start answering 429; a second share is unaffected; the correct password still unlocks.
- `python3 scripts/validate_changes.py` + `validate_doc_links.py` + `validate_coverage.py` green.

## Migration

**None.** The counter is in-memory and per-window, like the existing upload limiter — an empty budget on restart
is correct behaviour, not lost state.

## Definition of done

- A password-protected share answers 429 after N failed attempts, per share, and a tokenless resolve never
  consumes budget — proven by `SharePasswordAttemptBoundTest`, not by inspection.
- ADR 0037 states the bound veyq **has** (per-share failed attempts) instead of the one it does not (per-IP), and
  says plainly that password strength is client-enforced and unverifiable by the blind server.
- `cc.security.rate-limit.md` covers the sharing mechanism and no longer carries the 3 disproved claims.
- No legitimate guest path regressed (no existing assertion flipped; live smoke green).

## Retrospective

Hermetically done: `./gradlew cleanTest check` green (49 sharing tests, 0 flips); `SharePasswordAttemptBoundTest`
4/4 and **verified to bite** (disabling the counter reds 3 of the 4 rows); all six trust-chain guards green. No
contract change → no web work. **Live smoke passed** (user-run, no restart needed): repeated wrong passwords on a
share flip it to `429`, and the correct password still opens it.

- **I turned cr-0158's lesson into a worse bug by over-generalising it.** For the bite-proof restore I used `git
  checkout -- SharingHandler.kt` — the exact "prefer `git checkout` over `cp`" fix cr-0158's retro records, and
  the fix I'd *just* praised myself for after cr-0164's `cp -i` hang. But `git checkout --` resets to **HEAD**, and
  cr-0164 was never committed, so it silently discarded **both** CRs' edits to that file, not just the injection.
  cr-0158's lesson held only because *its* baseline was committed. The correct, context-independent rule: **before
  an injection, snapshot the current working file (`cp … /tmp/bak`) and restore from that snapshot non-interactively
  (`command cp -f`, bypassing the `cp -i` alias)** — the snapshot, not HEAD, is the thing to return to when the
  baseline is uncommitted. Recovery was clean (cr-0164's `/tmp/SharingHandler.bak` still held that CR's state; I
  re-applied cr-0165's edits via a single idempotent script that asserts each anchor matches exactly once), but it
  cost a full rebuild. **Next time:** one restore protocol for bite-proofs regardless of commit state — snapshot
  then `command cp -f`; never `git checkout` a file carrying uncommitted work.
- **The CR's "6 sites inherit the bound for free" undercounted the blast radius.** A fourth `PwCheck` value
  (`TOO_MANY`) makes every one of Kotlin's six exhaustive `when` blocks non-exhaustive, and five of the six outcome
  types needed a new `RateLimited` variant (only `ShareUploadOutcome` had one). "For free" was wrong: it is five
  new variants + six `when` arms — mechanical, but real, and the same shape cr-0164 added for `PasswordIncorrect`.
  The generalisable miss: "the counter lives in one place" (true) does not imply "the projection lives in one
  place" — an added enum case fans out to every exhaustive match on it. A refine that says "one counting site"
  should also count the *projection* sites the new outcome forces.
- **Deviated from the CR's phase order deliberately — built backend first, then wrote the docs against the
  verified code.** The plan lists docs as phase 1. But this whole review exists because docs drift from code, so I
  inverted it: the ADR/UC/cc numbers (default 10/window, per-share, only-on-wrong) are transcribed from the running
  implementation, not predicted. This is the right trade for a doc-accuracy CR and I'd repeat it; noting it because
  it is a conscious departure from the written plan, not an oversight.
- **`validate_doc_links` earned its keep again.** I guessed `cr-0130-rate-limit-account-creation.md`; the real
  slug is `cr-0130-auth-rate-limit-account-creation.md`. The guard caught it in one run. This is the cheap,
  structural version of the "verify checkable claims" rule — the link either resolves or it doesn't, no judgement.
- **Process note, user-ward:** like cr-0164 the live smoke is gated on the running instance, but here it needs
  **no** restart (no contract change), so it is a genuinely cheap check on `veyq.localhost:8081`: fire a handful of
  wrong passwords at one share and watch it flip to `429`, then confirm the correct password still opens it.
