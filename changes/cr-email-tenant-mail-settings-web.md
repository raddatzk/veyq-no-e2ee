---
id: cr-email-tenant-mail-settings-web
title: Web UI for tenant mail settings — SMTP form + invite-template editor (owner-gated, password write-only)
status: done
satisfies: ["uc.admin.tenant-mail-settings"]
adrs: ["0048", "0049", "0050"]
invariants:
  - "Operator-blind E2EE — the panel edits only operator-visible config (SMTP transport + owner-authored template prose over the closed {{link}}/{{domain}}/{{expiry}} set); it never touches a sealed field. No content crypto here."
  - "No usable secret in the client — the write-only SMTP password is consumed on submit and never held in state longer than the request, never rendered back, never reconstructed from a read (a read yields only passwordSet)."
  - "Self-hostable by default — a tenant with no/disabled SMTP (or no server KEK) keeps today's copy-link path; the UI states honestly when a config is not actually send-ready and why."
---

## Goal

Give a tenant **owner** the web form to configure their own SMTP + author the invite-mail template — closing the
gap Slice 1 (cr-email-smtp-transport) deliberately left open (inbox #13): today an owner can only configure this
via the API/seed. A new **owner-gated** `TenantMailSettingsPanel`, hung in the settings hub next to Terms, drives
the already-shipped `getTenantMailSettings` / `updateTenantMailSettings` endpoints. It edits the SMTP transport
(host, port, from, username, TLS + enabled toggles), handles the **write-only** password with the clean
"already set — leave blank to keep" pattern, and offers a **subject/body template editor** over the closed,
owner-visible variable set `{{link}}`/`{{domain}}`/`{{expiry}}` — surfacing the backend's write-time validation
error (`400 invalid_mail_template`) understandably instead of letting it fall through. Two honesty affordances the
brief calls for: the **KEK-unavailable** case (`503 mail_secret_unavailable`) reads as *"this server can't
encrypt secrets — an admin must configure the KEK"*, not a generic failure; and the panel makes the **actual
send-readiness** visible — `enabled` alone doesn't send, so it shows what's still missing (host / password /
sending-off) rather than leaving that silent. **Web-only**: backend + contract already stand; this adds no server
surface.

## Context & constraints

- **The backend + contract are done** (cr-email-smtp-transport, this branch): `getTenantMailSettings` /
  `updateTenantMailSettings` on `contracts/api/tenants.openapi.yaml` (schemas `TenantMailSettings` read /
  `TenantMailSettingsUpdate` write), the owner-gated `TenantMailSettingsHandler`, and the generated web client
  (`web/src/api/generated/tenants/apis/TenantsApi.ts` — both methods present). Confirm nothing server-side is
  missing before building: `grep -n "getTenantMailSettings\|updateTenantMailSettings" web/src/api/generated/tenants/apis/TenantsApi.ts`
  must be non-empty (the client is regenerated, gitignored — if empty, run `npm run generate:api` first).
- **Use-case:** [uc.admin.tenant-mail-settings](../docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md)
  — its `web` surface is exactly this CR (its coverage file currently gaps `web`). No contract/ADR change.
- **Mirror the Terms rubric exactly — same owner-gated settings-CRUD shape** ([`TenantTermsPanel.vue`](../web/src/features/admin/createTenant/TenantTermsPanel.vue)):
  `useAsyncData` load + `useAsyncAction` save, `useToast().success`, `handle401`, the `.veyq-surface .settings-panel`
  / `.settings-form` / `.settings-section-header` markup, PrimeVue `Button`/`Message`/`InputText`/`ToggleSwitch`
  imported per-file, and a **facade** wrapper in [`tenants.ts`](../web/src/features/admin/createTenant/tenants.ts)
  (views never touch the generator runtime). The two wiring sites a rubric needs in the hub are the ones Terms
  occupies: `grep -n "'terms'\|TenantTermsPanel" web/src/views/SettingsHubView.vue` (a rail item in the
  `ScopeKind.Tenant` block + a dispatch in `activeView`) — mail mirrors both.
- **Owner gate — derive it from the read, not a second call.** Unlike Terms (member-readable), the mail-settings
  **read is owner-gated**: `getTenantMailSettings` itself `403 not_tenant_owner`s a non-owner member (`404` a
  non-member). So a successful load ⇒ the caller is the owner (render the editor); a `403` ⇒ render an owner-only
  read-only notice (no scary error). This is simpler than Terms' `listMyTenants().role === 'OWNER'` probe and
  needs no extra request. (The rail item itself is shown to all tenant members like Terms; the panel gates.)
- **Password — write-only, consumed on submit (invariant #2).** The read never carries the password, only
  `passwordSet: boolean`. The field starts empty; a stored password shows a "leave blank to keep" hint when
  `passwordSet`. On save, the password is sent **only if the field is non-empty** (an empty field omits it ⇒ the
  backend keeps the stored ciphertext); the local field ref is **cleared immediately after** a successful save so
  no plaintext lingers in component state. Never bind the password into anything rendered back, never log it.
- **Template editor — closed variable set, error surfaced.** Subject + body are plain `<textarea>`s (like Terms'
  markdown editors). Show the owner the **allowed** variables (`{{link}}`, `{{domain}}`, `{{expiry}}`) as a hint.
  The closed-set validation lives **server-side** (an unknown `{{tag}}` ⇒ `400 invalid_mail_template`); the panel
  catches that coded error (`decodeHttpError` → `{status:400, code:'invalid_mail_template'}`) and shows a specific
  message naming the allowed set, distinct from a generic save failure. (No client-side re-validation of the tag
  set — the server owns the rule; the UI only translates its verdict, per the styleguide "validation belongs in
  the spec".) Empty templates ⇒ omit ⇒ veyq's EN+DE default (state this in the hint).
- **Honesty affordances (the brief's points 1 + 2), both web-only:**
  - **KEK unavailable (P1):** a save carrying a password against a server with no KEK returns
    `503 mail_secret_unavailable`. Catch that coded status and show *"the server can't encrypt secrets — an admin
    must configure the KEK"*, so a self-hoster without a KEK understands why saving fails — not a generic error.
  - **Send-readiness (P2):** the send-gate is `enabled ∧ host ∧ password-ciphertext ∧ KEK`
    ([SmtpTenantInviteMailer.kt](../backend/src/main/kotlin/com/veyq/app/features/admin/tenant/SmtpTenantInviteMailer.kt)).
    The read exposes `enabled`, `host`, `passwordSet` — and **`passwordSet: true` already implies the KEK was
    present at seal time** (without it the seal 503s and no password is ever stored), so `enabled ∧ host ∧
    passwordSet` is an honest client-side send-readiness signal in every realistic case. The panel renders it: a
    "ready to send email" state vs. an explicit checklist of what's still missing (sending is off / no host set /
    no password stored). The one residual case it cannot see — an operator removing the KEK *after* a password was
    sealed — is a send-time server misconfiguration, out of this panel's reach, and is **not** a reason to add a
    contract field (decided with the user: `sealAvailable` is deferred Kür, not correctness).
- **i18n EN+DE (parity is enforced).** Add a `mail: { … }` namespace block to **both** `en` and `de` in
  [`web/src/i18n/index.ts`](../web/src/i18n/index.ts) (the `de` object is typed `typeof en`, so a missing key
  fails typecheck) — mirror the `terms` block's key style (navTitle, editorSub, loading, loadFailed, save, saved,
  saveFailed, notOwner, …) plus the mail-specific keys (field labels, password-leave-blank hint, template
  variables hint, invalidTemplate, kekUnavailable, the send-readiness states).

## Phases

- [ ] **1 web — the owner-gated mail-settings panel, facade, hub wiring, i18n.**
  - Facade: add `getTenantMailSettings(tenantId)` + `updateTenantMailSettings(tenantId, update)` to
    `web/src/features/admin/createTenant/tenants.ts` (+ re-export `TenantMailSettings` / `TenantMailSettingsUpdate`),
    same thin-wrapper style as the terms fns.
  - `TenantMailSettingsPanel.vue` (next to `TenantTermsPanel.vue`): load via `useAsyncData` (success ⇒ owner ⇒
    editor; `403` ⇒ owner-only notice; other ⇒ load-failed `Message`); the SMTP fields; the write-only password
    with the leave-blank-keeps pattern (cleared after save); the subject/body template editor with the allowed-vars
    hint; save via `useAsyncAction` mapping `400 invalid_mail_template` / `503 mail_secret_unavailable` / `403` to
    distinct messages; the send-readiness indicator/checklist.
  - Hub: register a `mail` rail item in the `ScopeKind.Tenant` block + a `mail` dispatch in `activeView`
    (`SettingsHubView.vue`), mirroring `terms`.
  - i18n: the `mail:` block in `en` + `de`.
  - **Done when:** `npm run generate:api` then `npm run check` green (lint + typecheck + test + build).

- [ ] **2 web tests — the four behaviours the brief names.**
  - **Facade** (`mailSettings.test.ts`, mirror `terms.test.ts`): `getTenantMailSettings` addresses the tenant;
    `updateTenantMailSettings` shapes `{ tenantId, tenantMailSettingsUpdate: {…} }`; and the leave-blank-keeps
    wiring — an empty password omits `password` from the update, a typed one includes it.
  - **Panel** (`TenantMailSettingsPanel.test.ts`, mirror `TenantMembersPanel.test.ts`): (a) **password leave-blank**
    — a stored config (`passwordSet:true`) shows the leave-blank hint, saving with the field empty sends no
    `password`, saving with a typed value sends it and then clears the field; (b) **template validation error** —
    a save rejected with `400 invalid_mail_template` shows the specific allowed-variables message (not the generic
    one); (c) **KEK unavailable** — a save rejected `503 mail_secret_unavailable` shows the "server can't encrypt
    secrets — configure the KEK" message; (d) **send-readiness honesty** — `enabled:true` with no host or no
    `passwordSet` shows the "not send-ready + what's missing" state, and a complete config shows "ready"; plus the
    owner gate — a `403` load renders the read-only notice, not the editor.
  - **Done when:** `npm run check` green; the four cases assert the exact user-facing states.

- [ ] **3 coverage.**
  - `docs/coverage/features/admin/use-cases/uc.admin.tenant-mail-settings.yaml`: add `web` coverage to the
    form/owner-gate AC (`owner-configures-per-tenant-smtp`), the write-only/leave-blank AC
    (`password-sealed-at-rest` — the client half: omitting keeps), and the template AC
    (`invite-template-closed-variable-set` — the client error surfacing). Add **two new ACs** the web layer
    introduces — KEK-unavailable is surfaced understandably, and send-readiness is made honest/visible — each with
    its web test ref. Replace the `gaps.web` line (the deferred-UI note) now that the UI ships.
  - **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

`npm run generate:api` then `npm run check` green; `docs/coverage/` updated for `uc.admin.tenant-mail-settings`
(every new AC covered by a test, the `web` gap removed) with `python3 scripts/validate_coverage.py` green; **every**
trust-chain guard green (`for f in scripts/validate_*.py; do python3 "$f"; done`). i18n EN+DE parity holds
(typecheck enforces it). No new contract/backend surface. Inbox #13 reconciled (the deferred web-UI item closed).
The three things the brief stressed hold: the KEK dependency reads honestly, send-readiness is visible (not
silent), and the password never lingers in client state or a log.

**Not in scope:** a `sealAvailable`/`sendable` contract field (the residual KEK-removed-after-seal case — decided
with the user as deferred Kür, not correctness); deliverability/DNS ops (#38); KEK rotation (ADR 0050 limitation).

## Retrospective

- **Planning — I proposed a contract field before I'd reasoned it through, and the user's "Wofür brauchen wir das?"
  caught it.** My first fork framed `sealAvailable` as needed for both honesty points and claimed web-only would
  "degrade" them. That was wrong: I'd missed that `passwordSet: true` already implies the KEK was present at seal
  time (no KEK ⇒ the seal 503s ⇒ nothing is stored), so `enabled ∧ host ∧ passwordSet` is honest client-side in
  every realistic case, and the 503 covers the save-time KEK message. The user sparring, not my own check, exposed
  it. *Next time:* before proposing a new contract field to surface a server fact, trace whether an existing
  read field already implies it — the implication chain (`passwordSet ⇒ KEK-was-present`) was derivable from the
  handler I'd already read, not something I needed the user to find.
- **A real trap, caught in build, worth keeping: vue-i18n uses single-brace `{x}` interpolation, so the literal
  `{{link}}` template tokens could not live inside i18n messages.** I passed them as params instead (param values
  aren't re-interpolated) — the reasoning is a comment at the bite site in the panel, so it can't silently regress.
  The existing `terms.markdownHint` ships a bare `{contact}` that vue-i18n likely renders empty — a latent sibling
  of the same trap I deliberately avoided rather than copied.
- **Faithful-to-current-behaviour coupling I chose to leave, and flag.** The send-readiness logic requires a stored
  password, mirroring the *current* backend gate (`passwordCiphertext != null`). But inbox #13 already records a
  known open edge — a legit **passwordless** MTA wrongly falls back today. My UI is honest about what the backend
  *actually* does now; when that gate is fixed (send-hardening follow-up #2), the `hasPassword` requirement here
  must relax in the same change, or the UI will under-report readiness. Coupling named so the future fix updates
  both sites.
- **Process — conventions honored.** Grounded against the branch before building (backend/contract confirmed, not
  assumed); raised the one genuine design fork and waited rather than guessing; German conversation / English code;
  bite-adjacent — the coverage guard stays the completeness proof (the `web` gap can't silently reopen). One
  honest miss: I let the "reminder to use TaskCreate" prompts pass without a task list on a multi-step build; it
  stayed tractable, but a 3-item task list would have made the phase order legible to the user mid-run.
