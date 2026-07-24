---
id: cr-email-smtp-transport
title: Per-tenant SMTP for invite mail, configured as runtime settings (password sealed at rest)
status: done
satisfies: ["uc.spaces.add-member", "uc.admin.tenant-mail-settings"]
adrs: ["0048", "0049", "0050"]
invariants:
  - "Operator-blind E2EE — veyq interpolates only link + domain + expiry into a mail via a closed variable set, and never wires a sealed field into a render context; owner-authored template prose is a consented, operator-visible disclosure (ADR 0049)."
  - "No usable secret in a DB dump — the SMTP password is AES-GCM-sealed under an env KEK, never plaintext (ADR 0050)."
  - "Self-hostable by default — generic SMTP, no vendor SDK; a tenant with no SMTP ⇒ current copy-link behavior."
---

## Goal

Let each tenant configure its **own** SMTP as runtime settings (the way it configures Terms) so **member** invites
are emailed from the tenant's own domain — without an instance-wide mailer and without ever persisting a usable
secret. A per-tenant `SmtpTenantInviteMailer` resolves the tenant's settings at send time, unseals the SMTP
password (AES-256-GCM under an env KEK, ADR 0050), builds/caches a Vert.x `MailClient`, renders the tenant's
invite template (owner-authored, or veyq's bilingual EN+DE default) over a **closed variable set** — `{{link}}`,
`{{domain}}`, `{{expiry}}` only — and sends it (ADR 0049). A tenant with no/disabled settings falls
back to today's copy-link + dev-file path; the **owner-bootstrap** invite stays copy-link (a pending tenant has
no owner yet to configure SMTP, and there is no server-level default). No behavior changes for SMTP-less tenants
or the dev loop. This is the reshaped Slice 1 of the Email theme.

## Context & constraints

- **ADRs (flip Proposed → Accepted with this CR):** [0048](../docs/decisions/0048-provider-neutral-smtp-mail-transport.md)
  (per-tenant SMTP as runtime settings), [0049](../docs/decisions/0049-email-delivery-boundary.md) (what a mail
  carries + from-address + creds server-held), [0050](../docs/decisions/0050-operator-config-secret-encryption.md)
  (env-KEK AES-GCM seal — veyq's first server-held key).
- **Settings CRUD template — copy tenant-terms exactly:** `TenantTermsHandler.kt` (membership-gate → `findTenant`
  → `isOwner` → store), `TenantsResource.kt` (`getTenantTerms`/`updateTenantTerms`, owner-gated, 403
  `not_tenant_owner`), `V36__tenant_terms.sql`, and the `TenantTerms`/`TenantTermsUpdate` contract components.
  The auth gate, thin-resource, and store idiom transfer directly.
- **The ADR-0050 enabler is already built** (`ConfigSecretSeal` in `backend/.../shared/crypto/`, with its
  tamper/wrong-key/unconfigured guard tests): this CR consumes `ConfigSecretSeal.seal`/`unseal` to protect the
  SMTP password. Do not re-implement the seal here.
- **Secret rule (ADR 0050):** the password is **write-only** over the API (never returned — reads expose only a
  `passwordSet` boolean — and never logged); the DB columns hold only the `ConfigSecretSeal` `ciphertext + nonce`;
  the KEK is env (`VEYQ_SECURITY_CONFIG_SECRET_KEY`), never committed. Mirror the "never persisted, logged, or
  returned" posture the blob-store creds already document.
- **Port + fallback:** `backend/.../admin/tenant/TenantInviteMailer.kt` — the new `SmtpTenantInviteMailer`
  resolves the tenant by the `domain` already passed to `sendInvite` (unique per tenant); with no/disabled
  settings it **delegates to `LoggingTenantInviteMailer`** (copy-link + the cr-0046 dev-file), so
  `web/scripts/devSeed.mts awaitInvite()` and `seed:dev` are untouched (no dev tenant has settings).
  **CDI:** the port is single-impl today (`LoggingTenantInviteMailer` is injected directly into `TenantHandler`),
  so a second bean makes that interface injection ambiguous. Resolve it by marking `SmtpTenantInviteMailer`
  `@Alternative @Priority(1)` (a priority-enabled alternative wins the interface injection point globally in ArC)
  and injecting `LoggingTenantInviteMailer` **by concrete type** for the fallback (a concrete bean type is never
  ambiguous) — no other injection point or qualifier changes. The owner-bootstrap invite needs no call-site
  special-casing: a PENDING tenant has no settings, so it resolves to the fallback automatically.
- **Transport:** add `io.quarkus:quarkus-mailer` (BOM-managed) for the transitive Vert.x `MailClient`; construct
  it per-tenant at send time (build-per-send — a per-tenant client cache is a deferred optimization, no
  stale-config window). Not the static injected `quarkus-mailer` (ADR 0048 rejected instance-wide).
- **Template rendering (no new dependency):** the subject + body are owner-authored per tenant (optional; veyq
  ships a bilingual EN+DE default). Render them with a **minimal, in-house, logic-less `{{variable}}` substituter
  over a closed set** — `{{link}}`, `{{domain}}`, `{{expiry}}` only — not a full engine (ADR 0048 §6 rejected
  Qute/jmustache: sections/partials/lambdas are surface to audit against the ADR 0049 boundary). The renderer is
  handed a fixed three-key context and can reach nothing else; an unknown `{{tag}}` is a validation error at
  write time, not a silent blank. This closed context **is** the mechanical half of the ADR 0049 guarantee — the
  guard proves the render context is closed to those three non-secret keys and that no sealed field (scope /
  member / real-name / content) is wired in. Owner literal prose between the variables is a consented,
  operator/provider-visible disclosure (ADR 0049 §2) — out of the guard's reach by decision.
- **Contract impact (measured):** the invite endpoints are **unchanged** — the raw link is still returned for the
  copy-fallback (`tenants.openapi.yaml`, "the mailer is optional"). The **new** contract surface is only the
  mail-settings resource; every new field declares its `x-veyq-visibility` (ADR 0044) and the password appears in
  **no** response schema. The **template** fields (`subjectTemplate`, `bodyTemplate`) are read **and** write —
  unlike the password they are returned (the owner edits their own config), operator-visible config, not a
  secret — so their visibility differs from the password's write-only posture.
- **New use-case:** managing per-tenant mail settings warrants its own use-case `uc.admin.tenant-mail-settings`
  (mirror `uc.admin.tenant-terms`); created in Phase 1 with a coverage file, then added to `satisfies`.

## Phases

- [ ] **1 contracts + feature docs — the owner-gated mail-settings resource.**
  - `tenants.openapi.yaml`: `TenantMailSettings` (read: `host, port, from, username, tls, enabled, passwordSet,
    subjectTemplate, bodyTemplate`) + `TenantMailSettingsUpdate` (write: same, `password` **write-only,
    optional**; `subjectTemplate`/`bodyTemplate` optional — omitted ⇒ veyq's EN+DE default);
    `getTenantMailSettings` / `updateTenantMailSettings` on `TenantsResource`, owner-gated (403
    `not_tenant_owner`), password in **no** response. Tag every field's `x-veyq-visibility` (password never
    returned; the template fields **are** returned — operator-visible config, not a secret).
  - New use-case `docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md` (mirror tenant-terms) + a
    `status: planned` coverage file gapping the surfaces not yet built.
  - **Done when:** contracts generate; `validate_contracts_common.py`, `validate_field_visibility.py`,
    `validate_feature_format.py`, `validate_feature_wiring.py` green.

- [ ] **2 backend + infra — settings store, per-tenant send, dev sink, honest inventory.**
  - Migration `V<n>__tenant_mail_settings.sql`: `veyq_tenant_mail_settings` (tenant FK, `host, port, from_address,
    username, tls, enabled, password_ciphertext, password_nonce, subject_template, body_template` — nullable =
    not configured; a null template column ⇒ veyq's built-in EN+DE default).
  - `TenantMailSettingsHandler` (tenant-terms gate): read returns non-secret fields + `passwordSet` + the
    template fields; write seals the password (ADR 0050) and **never** returns/logs it. A template that
    references any `{{tag}}` outside the closed set is **rejected at write time** (400), never stored.
  - `InviteMailRenderer` (new, in-house): a minimal logic-less `{{variable}}` substituter over a **fixed
    three-key context** (`link`, `domain`, `expiry`) — no sections/partials/lambdas, no reflection, no way to
    reach a sealed field; unknown `{{tag}}` ⇒ rejected (feeds the write-time validation above). Ships the EN+DE
    default subject + body used when a tenant has no custom template. This closed context is the mechanical half
    of the ADR 0049 boundary.
  - `SmtpTenantInviteMailer` behind the port: resolve settings by `domain`; enabled+configured ⇒ unseal, build/
    cache a Vert.x `MailClient`, render the tenant's template (or default) via `InviteMailRenderer` over
    `{link, domain, expiry}`, send from `noreply@<domain>`/configured `from`; else delegate to
    `LoggingTenantInviteMailer`. Add `io.quarkus:quarkus-mailer`.
  - `infra/docker-compose.yml`: `mailpit` under `profiles: ["mail"]` (`11025:1025`, `18025:8025`); `infra/README`
    note. `docs/crosscutting/security-model.md`: add the account email + mail send-fact + the server-held,
    at-rest-sealed SMTP secret **+ the owner-authored template text (operator-visible)** to the metadata
    inventory (ADR 0049/0050; closes the #43-flagged doc gap).
  - **Tests (promoted guards):** (a) **content boundary** — `InviteMailRenderer`'s context is closed to
    `{link, domain, expiry}`: an unknown `{{tag}}` is rejected, and no sealed field (scope/member/real-name/
    content) is reachable by interpolation — proved at the renderer + a sent mail (Vert.x mail capture /
    MockMailbox) carrying only the substituted non-secret values; (b) **password never plaintext** — the stored
    column is ciphertext (not the input), and neither the GET response nor any log contains it; (c) unconfigured
    tenant ⇒ copy-link fallback + dev-file still written.
  - **Bite-proof** the content-boundary (closed render context) and password-plaintext guards.
  - **Done when:** `./gradlew -p backend check` green; guards bite; `seed:dev` still works.

- [ ] **N coverage.**
  - AC on `uc.spaces.add-member`: *"a member invite is delivered by email when the tenant's SMTP is configured
    (from the tenant's own address; body rendered from the tenant's template or veyq's default over the closed
    `{{link}}`/`{{domain}}`/`{{expiry}}` set — veyq injects nothing sealed); with no config the copy-link path is
    unchanged."* Fill the `uc.admin.tenant-mail-settings` coverage, including an AC that a template referencing a
    `{{tag}}` outside the closed set is **rejected at write time**. Add both to `satisfies`.
  - **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` green. `docs/coverage/` updated for `uc.spaces.add-member` + `uc.admin.tenant-mail-settings`,
and **every** guard green (`for f in scripts/validate_*.py; do python3 "$f"; done`). The correctness rests on two
structural invariants, each proved by a promoted, bite-tested guard: the ADR-0049 **content boundary** (no
scope/member field in a mail) and the ADR-0050 **no-plaintext-secret** (the password is only ever ciphertext in
the DB / absent from responses+logs). SMTP-less tenants and `seed:dev` are unchanged; the owner-bootstrap invite
stays copy-link. ADRs 0048/0049/0050 flipped to Accepted.

**Deferred to a follow-up slice:** the web settings UI — including the SMTP **and** the invite-template editor;
a tenant owner configures both via the API/seed for now, and the template field + its write-time validation ship
here at the contract/backend layer. Also deferred: deliverability ops / DNS alignment for per-tenant
from-addresses (#38); KEK rotation (ADR 0050 limitation).

## Retrospective

- **Planning — the CR I was told to "write" already existed.** The scoping brief described authoring ADR + CR
  from scratch, but a prior refine session had already shipped ADR 0048/0049/0050 + this CR. The brief did warn
  "the repo moves — verify"; grounding caught it before I wrote a duplicate, so no harm. *Next time:* when a task
  says "create X", `grep`/`ls` for X's existence as step 0, before any planning — the cheapest possible check.
- **A mid-flight request expanded the slice past its own scoping boundary.** The owner-editable-template ask
  arrived after the ADRs were refined and collided head-on with ADR 0048 §6 (no template engine) + ADR 0049 §2
  (content boundary). Surfacing it as a fork (not silently building it) was right, and the sparring caught a real
  misconception — "mustache with fixed variables" makes the *variables* safe but not the *literal prose* around
  them, which is the actual operator-visible leak. But I folded the expansion into Slice 1 on the user's steer;
  the honest alternative — land the transport enabler, make owner-templates its own slice — deserved equal
  billing as option 1. *Next time:* when a request grows a tightly-scoped slice, offer the split first.
- **Same-session refine+implement against the split guidance — a deliberate, flagged trade.** The workflow keeps
  design-heavy CRs split (a clean-room implementer proves completeness); this one amended two invariant ADRs and
  added a new renderer abstraction, yet ran refine+implement in one session per explicit direction. The named
  exception's condition — a strong automatic gate standing in for the clean-room proof — was met (bite-tested
  content-boundary + password-plaintext guards + `./gradlew check` 421), but the scope growth pushed it past the
  "tightly-scoped, mechanical" ideal the exception assumes. Worth naming, not hiding.
- **Doc-vs-code honesty caught in the act.** The ADR/CR first said the per-tenant `MailClient` is "cached,
  invalidated on settings change"; I implemented build-per-send (no stale-config window, invites are rare) and
  edited the docs to match the *code*, not the reverse. Caught only because I was writing the code and the doc in
  the same session — the exact overlap the split distrusts, cutting the other way this time.
