---
id: cr-email-invite-send-hardening
title: Harden the tenant-invite email send path — delivery result out of the transaction + invite rate limiting
status: done
satisfies: [uc.spaces.add-member, uc.spaces.manage-member-invites, uc.admin.create-tenant]
adrs: [0048, 0049]
invariants: [operator-blind-e2ee]
---

## Goal

Slice 1 (`cr-email-smtp-transport`, committed `0449e7be`) already **sends** tenant invites over per-tenant SMTP.
This CR hardens that send path, in two independent parts:

- **Part A — delivery result + send out of the transaction.** Today the SMTP send blocks up to 30 s **inside the
  `@Transactional` mint method** (`SmtpTenantInviteMailer.deliver` `.get(30, SECONDS)`), so a hung SMTP server holds
  a DB connection + open transaction for up to 30 s — a real correctness defect. And the send is `void` and
  **swallows** failures, so an owner cannot tell a delivered invite from a silently-dropped one. Fix both: commit
  the mint, **then** send outside the transaction with a shorter timeout, and return a first-class result
  (`SENT` / `FALLBACK_COPYLINK` / `FAILED`) that the web surfaces so the owner always knows what happened.

- **Part B — invite rate limiting.** No mint/send path is rate-limited today (`@RateLimited` sits only on the
  unauthenticated *accept* side). An authenticated tenant owner can drive unbounded outbound mail, and the
  reissue loop against one address is an email-bombing vector. Add a dedicated handler-level limiter keyed
  **per (tenant, recipient)** and **per tenant**, on the two tenant-owner member-invite paths that actually drive
  SMTP mail.

Deliberately **not** here (each a named follow-up, see § Follow-ups): raw-token suppression, the passwordless-SMTP
gate edge, the password-couples-to-sendable edge, the SMTP/template settings web UI, and the general
declarative-`@RateLimited`-posture-guard refactor.

## Context & constraints

Decisions resolved in the Slice-2 refine (2026-07-24), restated in § Key design decisions below. Durable state this honors:
- **Use-cases:** [`uc.spaces.add-member`](../docs/features/spaces/use-cases/uc.spaces.add-member.md),
  [`uc.spaces.manage-member-invites`](../docs/features/spaces/use-cases/uc.spaces.manage-member-invites.md),
  [`uc.admin.create-tenant`](../docs/features/admin/use-cases/uc.admin.create-tenant.md).
- **ADRs:** [ADR 0048](../docs/decisions/0048-provider-neutral-smtp-mail-transport.md) (per-tenant SMTP, copy-link
  stays a channel), [ADR 0049](../docs/decisions/0049-email-delivery-boundary.md) (operator-blind mail boundary).
- **Contract:** [`contracts/api/tenants.openapi.yaml`](../contracts/api/tenants.openapi.yaml).
- **Invariant at risk — operator-blind.** The delivery result is **request-scoped and returned inline; it is
  never persisted.** No new stored metadata, no DB column, no provider-response body — so **ADR 0049 needs no
  change** (it only would if a *persisted* send/bounce status were introduced). The rate-limiter state is
  in-memory and ephemeral; the recipient address it keys on is already server-visible plaintext (ADR 0049 §4).

### Key design decisions (from the refine)

1. **Send offloading: synchronous, outside the transaction, ~10 s timeout.** Async offloading (the `TasksStore`
   job pattern) is explicitly deferred — invites are a rare single-recipient owner action and the owner always
   holds the copy-link fallback, so block-until-sent is the V1 sweet spot. Mechanism: the mint runs in an explicit
   `QuarkusTransaction.requiringNew().call { … }` block in the handler (the handler methods drop their
   `@Transactional` annotation); the send runs **after** that block returns (tx committed). This keeps everything
   in one handler method/bean and **avoids the CDI self-invocation trap** (a `this.mint()` call would not go
   through the proxy, so an in-bean `@Transactional` split would silently share one transaction). There is no
   existing programmatic-tx / after-commit precedent in the backend — this introduces `QuarkusTransaction`
   (narayana-jta, already on the classpath transitively via `quarkus-hibernate-orm-panache-kotlin`).

2. **Delivery result is a 3-state enum**, returned inline in the mint response:
   - `SENT` — SMTP configured + send succeeded.
   - `FALLBACK_COPYLINK` — no sendable SMTP (unconfigured tenant, or the owner-bootstrap PENDING tenant with no
     mail settings); today's copy-link path, unchanged.
   - `FAILED` — SMTP configured but the send threw (was silently swallowed before). The copy-link the response
     already carries (`bootstrapSecret`, kept per the refine) is the failure fallback.

3. **Rate-limit scope: the two tenant-owner member paths only** — `inviteTenantMember` + `reissueMemberInvite`.
   The Server-Owner bootstrap paths (`createTenant` / `resendTenantInvite` / `reassignTenantInvite`) are **left
   unlimited**: a PENDING tenant has no mail settings, so those paths always hit the copy-link fallback and send
   **no** SMTP mail — there is no bombing to throttle, and they are Server-Owner-gated (the operator themselves).
   *(Decided with the owner 2026-07-24 — the build order's "resend" named a bootstrap, no-mail path.)*

4. **Two rate-limit keys, dedicated handler-level limiter.** The bombing guard needs **per-(tenant, recipient)**
   keying, which the per-IP `@RateLimited`/`AuthRateLimitFilter` machinery **cannot** express. So the real
   protection is a dedicated `InviteMailRateLimiter` — a sibling to `ShareRateLimiter`/`SharePasswordAttemptLimiter`
   but built on the **pure, mutation-tested `RateLimitWindow`** core (the share limiters reimplement the window
   inline; do not copy that — reuse `RateLimitWindow`). Keys:
   - **per (tenantId, recipientEmail)** — the primary anti-bombing guard (the tighter one);
   - **per tenantId** — the volume cap against an owner-as-sender.
   Consumed in the handler **after** the authorization guards pass and **before** the mint, so a limited request
   creates no orphan invite and only authorized owners consume a slot. Over-limit → a `RateLimited(retryAfter)`
   outcome → **429 + `Retry-After`** (the user asked for the header explicitly; compute it via
   `RateLimitWindow.retryAfter`). Recipient address is keyed on the **same `.lowercase()` normalization the mint
   uses** — no `+`-tag stripping in V1 (revidierbar).

5. **~~Baseline `@RateLimited` on the two mint endpoints~~ — tried, reverted (it did not fit cleanly).** The plan
   was to also mark `inviteTenantMember` + `reissueMemberInvite` with `@RateLimited` as a per-IP baseline + guard
   anchor. It does **not** fit cleanly and was **removed**: the per-IP `AuthRateLimitFilter` shares **one global
   per-IP window across all `@RateLimited` endpoints**, so adding the authenticated member endpoints (a) coupled
   authenticated-owner traffic to the *unauthenticated* auth budget — an active owner could trip the **login**
   limit — and (b) broke `AuthRateLimitTest` in the full suite (the shared 127.0.0.1 window was polluted by the
   new endpoints' test traffic). Per the owner's "wenn das sauber passt" condition, it was dropped. The dedicated
   `InviteMailRateLimiter` is the real (and only needed) protection; the guard anchor properly belongs to the
   future **declarative auth+rate-limit posture annotation** (its own refine — see § Follow-ups), which can express
   per-owner/per-recipient keying the per-IP annotation cannot.

6. **Rate-limit defaults are deliberately set, not empirically calibrated — revidierbar.** Config bucket
   `veyq.ratelimit.invite.*` (note: `ratelimit`, no hyphen — matches the existing `veyq.ratelimit.auth.*`).
   Starting values: **3 per recipient / 20 per tenant, per 3600 s window.** Marked revidierbar in code + config.

## Phases

- [ ] **1 contracts** — In `contracts/api/tenants.openapi.yaml`: add a named `DeliveryResult` string schema
      (`enum: [SENT, FALLBACK_COPYLINK, FAILED]`, `x-veyq-visibility: enum`, mirroring `TenantStatus`/`InviteStatus`);
      add it as a **required** `delivery` property on `TenantCreated`, `TenantInviteState`, `MemberInviteState`
      (every mint produces a result, so required is honest — the three schemas are returned only by the five mint
      operations, not by any read endpoint). · **Done when:** `./gradlew openApiGenerateSpecs` (backend) and
      `npm run generate:api` (web) regenerate cleanly; `for f in scripts/validate_*.py; do python3 "$f"; done` green.
      Locators: `grep -n "TenantCreated:\|TenantInviteState:\|MemberInviteState:\|InviteStatus:" contracts/api/tenants.openapi.yaml`.

- [ ] **2 backend** — Part A + Part B.
      - *Part A:* `TenantInviteMailer.sendInvite(...)` returns a backend `DeliveryResult` enum (SENT/FALLBACK_COPYLINK/
        FAILED). `LoggingTenantInviteMailer` returns `FALLBACK_COPYLINK`; `SmtpTenantInviteMailer` returns
        `FALLBACK_COPYLINK` on the not-sendable delegation, `SENT` on success, `FAILED` on the caught send exception
        (no longer swallowed to `void`; still logged secret-free), and `SEND_TIMEOUT_SECONDS` 30 → 10. In
        `TenantHandler`, wrap each mint in `QuarkusTransaction.requiringNew().call { … }`, drop the method-level
        `@Transactional`, call `mailer.sendInvite(...)` **after** the block, thread the result onto the four Done/
        Created outcomes. `TenantsResource` maps the delivery result into the DTO at the four construction sites.
      - *Part B:* new `InviteMailRateLimiter` (per-(tenant,recipient) + per-tenant, on `RateLimitWindow`), config
        `veyq.ratelimit.invite` in `VeyqConfig` + `application.properties`. Consume it in `inviteMember` +
        `reissueMemberInvite` (after authz, before mint); add a `RateLimited(retryAfterSeconds)` variant to
        `MemberInviteOutcome` + `ReissueOutcome`; `TenantsResource` maps it to 429 + `Retry-After`. (The per-IP
        `@RateLimited` baseline was tried and reverted — decision 5.)
      - **Done when:** `./gradlew check` green (incl. the new tests below).
      - Locators: mailer call sites `grep -rn "mailer.sendInvite" backend/src/main`; DTO delivery sites
        `grep -n "bootstrapSecret" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantsResource.kt`;
        existing limiter shape `backend/src/main/kotlin/com/veyq/app/features/sharing/ShareRateLimiter.kt`;
        config bucket precedent `grep -n "ratelimit\|WithDefault" backend/src/main/kotlin/com/veyq/app/shared/config/VeyqConfig.kt`.

- [ ] **3 web** — After `npm run generate:api`, branch the three invite dialogs on `delivery`:
      `SENT` → "Einladung an {email} gesendet" (copy-link demoted to an optional expander), `FALLBACK_COPYLINK` →
      today's copy-link block unchanged, `FAILED` → "Zustellung fehlgeschlagen — kopiere den Link" + the copy field.
      Add i18n keys in **both** the en and de blocks (`setLocale.test.ts` guards parity; de informal "du"), reusing
      the existing `members.inviteSent` / `tenant.inviteSent` / `copyLinkIntro` / `copyLink` keys where they fit.
      Handle a **429** on the invite/reissue calls (a "zu viele Einladungen — später erneut" message). · **Done
      when:** `npm run check` green. Locators: `grep -rln "bootstrapSecret" web/src/features`;
      dialogs `web/src/features/spaces/InviteMemberDialog.vue`,
      `web/src/features/admin/createTenant/CreateTenantDialog.vue`,
      `web/src/features/admin/createTenant/TenantDetailPanel.vue`.

- [ ] **4 coverage** — Update the coverage YAML(s) for each `satisfies:` use-case (and the mirrored acceptance
      criteria in the UC `.md`): a delivery-result AC (SENT/FALLBACK/FAILED) on add-member + manage-member-invites
      + create-tenant; a rate-limit AC (over-limit → 429 + Retry-After, no invite minted) on add-member +
      manage-member-invites. · **Done when:** `python3 scripts/validate_coverage.py` green and
      `for f in scripts/validate_*.py; do python3 "$f"; done` green.

## Tests (bite-oriented)

- **Delivery result, three states** — extend the GreenMail harness (`SmtpInviteMailSendTest`): configured tenant →
  outcome `SENT`; unconfigured tenant → `FALLBACK_COPYLINK`; configured tenant + unreachable SMTP (dead port /
  stopped GreenMail) → `FAILED`. The `FAILED` case doubles as the **out-of-tx proof**: assert the invite still
  persists after a failed send (the mint committed before the send ran).
- **Rate limiter bite** — the case that must make the guard FAIL: the 4th invite to the same (tenant, recipient)
  within the window (max 3) → `RateLimited` with a non-null `retryAfter`; and the per-tenant cap independently.
  A unit test on `InviteMailRateLimiter` (over-limit → retryAfter) + a handler/slice assertion that no invite is
  minted on a limited call, and that the response is 429 + `Retry-After`.

## Definition of done — met

`./gradlew check` (green) + `npm run check` (764 web tests green) + `for f in scripts/validate_*.py; do python3
"$f"; done` (14 guards green). Coverage updated for all three `satisfies:` use-cases with the new ACs covered by
the tests above. No new persisted metadata → operator-blind + ADR 0049 unchanged. (Decision 5's `@RateLimited`
guard-anchor was reverted — the posture-declaration guard moves to the declarative-annotation follow-up; there is
no structural invariant this CR promotes, since the limiter is behavioural and covered by its bite tests.)

## Follow-ups (record in the inbox, do NOT build here)

- **Raw-token suppression** (`bootstrapSecret` `required` → optional when SMTP is configured) — a contract change
  gated on its own decision; the token stays functionally load-bearing as the `FAILED`/`FALLBACK` fallback until
  then. Now *possible* after Part A, but a deliberate separate step.
- **Declarative rate-limit annotation + per-endpoint posture guard** — a future refine: an annotation that can also
  express per-owner/per-recipient keying, plus a `validate_*` guard that forces every endpoint to declare its auth
  + rate-limit posture (with an explicit, justified opt-out for the few that need none). This CR's dedicated
  limiter + `@RateLimited` baseline are built so that guard won't have to retrofit them.
- **Passwordless-SMTP gate edge** (§5 of the scoping doc) — a legit passwordless MTA wrongly falls back to
  copy-link (`SmtpTenantInviteMailer` gates on `passwordCiphertext != null`, but `deliver` treats the password as
  optional). A one-line fix, left as a known point per the owner.
- **Password-couples-to-sendable edge** — the same coupling, noted, not touched.
- **SMTP + template settings web UI** — the Slice-1-deferred `cr-email-tenant-mail-settings-web`.

## Retrospective

- **Planning miss — decision 5 (`@RateLimited` baseline) was authored as a "will do", not a "tried".** The refine
  wrote it as settled ("add it + extend `RateLimitedEndpointsTest`"), but the per-IP `AuthRateLimitFilter` shares
  **one global per-IP window across all annotated endpoints** — a fact neither the refine nor the owner's direction
  weighed. Marking authenticated owner endpoints coupled them to the *unauthenticated* auth budget and broke
  `AuthRateLimitTest` in the full suite. Root cause: the annotation's shared-window semantics were assumed additive
  when they are global. *Next time:* before promising an annotation as a guard-anchor, read what its filter keys on
  and whether its budget is shared — a per-IP filter is not a free per-endpoint decorator.
- **Execution miss — generic test seed secrets collided on a global unique index.** New slice tests seeded invites
  with `"s0"`/`"s1"`; the JVM-lived H2 has a **global** `UX_TENANT_INVITE_TOKEN_HASH`, so those hashes collided
  with other classes' seeds — green in isolation, `RollbackException` in the full run. The existing convention
  ("distinct domains/emails so the mem DB can't bleed state") already implied it but I read it as *domains/emails*,
  not *secrets*. *Next time:* treat the seed **secret** as a globally-unique fixture too (the token_hash index is
  suite-wide), and run the *full* `./gradlew check`, not just the touched classes, before calling backend done.
- **Process went well — the design-fork check-in paid off.** Surfacing the rate-limit endpoint-set fork (the
  owner's "resend" named a no-mail bootstrap path) before writing the CR avoided building the wrong scope; the
  owner's mid-flight direction (dedicated limiter + defer the general annotation guard) slotted in cleanly. The
  QuarkusTransaction approach was flagged up-front and confirmed, so the tx refactor carried no surprise.
- **Convention honored:** German conversation + English code/docs/commits; bilingual UC "what this means for you"
  left intact; operator-blind kept (delivery result never persisted, ADR 0049 untouched); reused `RateLimitWindow`
  rather than duplicating the window like `ShareRateLimiter` did.
