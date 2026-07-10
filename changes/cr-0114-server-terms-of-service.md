---
id: cr-0114
title: Server terms of service — operator tenant terms + a member accept-gate (server-side)
status: done
satisfies: [uc.admin.tenant-terms, uc.account.accept-terms]
adrs: ["0035"]
invariants: [operator-blind-e2ee]
---

## Goal

Give each server operator a place to state their **terms** and require members to **accept** them — adopting the
pre-E2EE tenant-terms model, operator-blind. Each tenant carries **per-locale (DE+EN) markdown terms** +
a **`terms_version`**; the **owner** edits them (from a clause template or freely), a *normalized change* bumps the
version; a **member** must **accept the current version** to enter and **re-accept** on a bump, recorded
**server-side** as operational metadata. **v1 = member-facing** — the anonymous public-share client-side notice is
a later slice ([ADR 0035](../docs/decisions/0035-server-terms-of-service.md),
[uc.admin.tenant-terms](../docs/features/admin/use-cases/uc.admin.tenant-terms.md),
[uc.account.accept-terms](../docs/features/account/use-cases/uc.account.accept-terms.md)).

## Context & constraints

- **Operator-blind (invariant) — no tension.** The terms are the operator's **public policy** (served to everyone,
  including **pre-auth** on the login page), **never E2EE content**; a member's **`accepted_terms_version`** is
  **coarse operational metadata** (like membership/role). Recorded in the
  [security-model](../docs/crosscutting/security-model.md) operational-metadata list.
- **Per-tenant, owner-edited, versioned.** `veyq_tenant` gains **`terms_markdown_by_locale`** (TEXT, a serialized
  locale→markdown map) + **`terms_version`** (int, default 0). Save **normalizes** and bumps `terms_version`
  **only when the markdown changed** (idempotent re-saves don't bump — the pre-E2EE logic). Editing is
  **owner-gated** (`isOwner`).
- **Acceptance in a dedicated table.** `veyq_tenant_terms_acceptance` keyed **`(user_id, tenant_id)`** →
  `accepted_version` + `accepted_at` — an upsert, independent of the multi-row `veyq_membership` relation and off
  the trust-chain-guarded membership row (a member has several membership rows per tenant; `veyq_user` is the
  wrong grain — acceptance is per-tenant).
- **The gate needs a session, not an unlocked vault.** Acceptance is operational, so the member accept-gate sits
  **above unlock** — a **web router guard** (after the `requiresUnlock` check) redirects a member with a stale
  `accepted_terms_version` to a `/terms` accept route (parallels the operational Server-Owner settings route).
- **Clause template = the pre-E2EE 11-clause set** (purpose, privacy, rights, children, illegal/CSAM, share-links,
  guest-uploads, availability, review-&-removal, external, contact-with-`{contact}`), DE **neutral-first**
  ([cc.inclusive-language](../docs/crosscutting/cc.inclusive-language.md)). **Impressum** = a clause.
- **Anonymous public-share acceptance = OUT of scope** — an accountless visitor's acceptance is **client-side**
  (localStorage), a later [sharing](../docs/features/sharing/_index.md) slice on top of these terms.
- **Optional capability gate.** A `admin.tenant-terms` capability id may ride `/api/setup` (ADR 0032) to soft-gate
  whether the terms UI renders instance-wide — distinct from the per-tenant content (which does **not** ride
  `/api/setup`, an instance-wide/pre-auth/per-caller-identical surface).
- **Verify:** backend `./gradlew check` (slice tests below); web `cd web && npm run check`; the three trust-chain
  guards; a live member-gate smoke (owner sets terms → a member is gated → accepts → re-gated after a bump).

## Phases

- [x] 1 **contracts** — on the tenants spec: `GET` current-locale **terms + `termsVersion` + the caller's
  `acceptedVersion`** (authed); `PUT` **owner-gated** terms update (`termsMarkdownByLocale`); `POST` member
  **accept** (records the current version). Regen backend + web clients — **never hand-write DTOs**. · **Done:**
  `getTenantTerms`/`updateTenantTerms`/`acceptTenantTerms` + `LocalizedMarkdown`/`TenantTerms`/`TenantTermsUpdate`/
  `AcceptTermsRequest` on `tenants.openapi.yaml`; codegen green both sides (backend `openApiGenerateSpecs`, web
  `generate:api`).
- [x] 2 **backend** — migration: `veyq_tenant` += `terms_markdown_by_locale` (TEXT) + `terms_version` (int NOT NULL
  DEFAULT 0); a `veyq_tenant_terms_acceptance` table (`user_id`, `tenant_id`, `accepted_version`, `accepted_at`).
  `TenantStore.updateTerms` (normalize + bump-on-change), `acceptTerms` (upsert), the current-terms/accepted read;
  `TenantTermsHandler` (owner-gate the update, membership-gate accept/read) + `TenantsResource`. · **Done:** V36
  migration + `TenantTermsAcceptanceEntity` + `TenantTermsHandler` (JSON-serialized locale map, TreeMap-canonical
  normalize); `./gradlew check` green (278 tests) incl. `TenantTermsSliceTest` (owner bump / idempotent no-bump /
  non-owner 403 / member accept + re-stale + stale-409 / non-member 404).
- [x] 3 **http** — `.http` assertions for the boundary + lifecycle (owner update → bump; idempotent no-bump;
  non-owner refused; member accept; re-accept after a bump), with response-handler assertions. · **Done:**
  `http/admin/tenant-terms-authed.http` (signed owner creates+activates a self-owned tenant, then the full terms
  lifecycle) — **`RUN SUCCESSFUL`, 13/13** against a live isolated instance. Non-owner 403 is covered by the slice
  test (needs a second signed identity, out of ijhttp scope).
- [x] 4 **web** — the **owner terms editor** (settings hub `tenant` category → `TenantTermsPanel`: clause template
  + free markdown, per-locale DE/EN, `{contact}`) and the **member accept-gate** (a router guard + a `termsStale()`
  reactive on `activeSpace` refreshed in `enterScope`, plus a cold-boot watcher → the `/terms` `TermsAcceptView`;
  accept → record → back to the remembered route). i18n `terms:` namespace (DE du-form, gender-neutral). · **Done:**
  `npm run check` green (typecheck + 539 tests + build). **Browser smoke passed** (with the user's explicit consent
  to use the local dev creds): the owner sets terms → **version 1**; navigating home re-fires the gate → the
  `/terms` full-screen card renders the DE terms; **accept** → back to the shell; a **reload** confirms the
  acceptance persisted **server-side** (a fresh `enterScope` read does not re-gate).
- [x] 5 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries the terms +
  acceptance after regen, so the native terms editor + gate are each platform's own later CR.

## Status note (implemented 2026-07-10)

Shipped **contracts → backend → http → web**, all green:
- **Contracts:** three tenant terms endpoints + four schemas; both clients regenerated.
- **Backend:** V36 migration (terms columns on `veyq_tenant` + `veyq_tenant_terms_acceptance`), `TenantTermsHandler`
  (owner-gated update with normalize + bump-on-change; membership-gated read/accept; a stale accept → `409
  terms_version_changed`), `TenantsResource` (three `@Authenticated` ops). `./gradlew check` = **278 tests, 0
  failures**, incl. the 4-case `TenantTermsSliceTest` over the full HTTP stack.
- **http:** `tenant-terms-authed.http` — **13/13** live (`ijhttp` against a fresh isolated H2 instance on :8099, so
  the user's dev MariaDB + demo data were untouched).
- **web:** data-layer facade (`getTenantTerms`/`updateTenantTerms`/`acceptTenantTerms`), `TenantTermsPanel` (owner
  editor, non-owners read-only) wired into the settings hub `tenant` category, `TermsAcceptView` (`/terms`
  full-screen gate), the router guard + cold-boot watcher + `activeSpace.termsStale()`/`markTermsAccepted()`,
  `termsTemplate.ts` (the 11-clause DE+EN starter), and the `terms:` i18n namespace. `npm run check` green.

**Design choices worth recording:**
- **Fail-open gate.** A transient terms-fetch error leaves `termsVersion`/`acceptedVersion` at 0, so `termsStale()`
  is false — a member is never locked out of the shell by an API hiccup (terms acceptance is an operational gate,
  not a confidentiality control).
- **Cold-boot timing.** The tenant is entered (`enterScope`) *after* `beforeEach` already let `/` through, so the
  guard alone can't gate on a cold load; a `watch(termsStale, …)` in the router re-fires the gate once the terms
  resolve. Warm navigations gate synchronously in `beforeEach`.
- **Normalize before compare.** The owner update trims + newline-normalizes each locale into a sorted-key JSON map
  before comparing to storage, so a whitespace-only re-save doesn't bump the version (the pre-E2EE logic).
- **Acceptance grain.** A dedicated `veyq_tenant_terms_acceptance` (surrogate id + `UNIQUE(user_id, tenant_id)`),
  independent of the multi-row `veyq_membership` — `veyq_user` is the wrong grain (acceptance is per-tenant).

**Still open (later slices):** the **anonymous public-share client-side notice** (localStorage on the share page)
and the **native** editor + gate (apple/android) — both intentionally out of this CR.

## Definition of done

Each tenant carries **owner-editable per-locale terms** + a **`terms_version`** (bumped on a normalized change);
a **member accept-gate** (server-side `accepted_terms_version`, hard gate + **re-accept** on a bump) blocks entry
until accepted, all **operator-blind** (terms = public operational content, acceptance = coarse metadata). Ships
**contracts → backend → http → web**; `./gradlew check` + `npm run check` + the three trust-chain guards green;
a live member-gate smoke passes. The **anonymous public-share client-side notice** and **native** surfaces remain
later slices.
