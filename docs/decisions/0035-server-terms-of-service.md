# ADR 0035 — Server terms of service: operator-set tenant terms + a member acceptance gate (server-side), anonymous acceptance client-side

- Status: Accepted
- Date: 2026-07-10

## Context

veyq is **operator-blind** and **self-hostable**: each server is run by an **operator** (the central infra is
one operator among many). There is today **no surface where an operator states their policy** — acceptable use,
prohibited/illegal content, review-and-removal, contact, an Impressum — nor where users **consent** to it. The
fresh state already earmarks this: the admin feature "**Set tenant branding & legal terms — planned**"
([features/admin/_index](../features/admin/_index.md)), the sharing feature's deferred "**share notices / tenant
terms**" ([features/sharing/_index](../features/sharing/_index.md)), and the **T&S "decryptable-on-report"
corollary** in the [security model](../crosscutting/security-model.md) (a reported public share is decryptable).

The **pre-E2EE product** shipped exactly this: `Tenant.termsMarkdownByLocale` (DE+EN) + `Tenant.termsVersion`
(bumped on edit), assembled from a **clause template** (privacy, rights-to-content, prohibited/illegal incl.
CSAM, share-links, review-and-removal, external services, contact-and-deletion) and shown on shares with the
version. We **adopt that model** (the refine's ruling, like the buckets adoption).

**No tension with operator-blindness.** The terms are the operator's **public policy** (served to everyone,
including pre-auth on the login page), and acceptance is **coarse operational metadata** (who accepted which
version when) — never user content. It is in fact the one honest surface where the content-blind operator states
policy + the honest-operator caveat + the public-share decryptable-on-report.

**The acceptance-storage split (the refine's key nuance).** An authenticated **member** has an account, so the
server records their accepted version. An anonymous **public-share visitor** has **no account**, so the server
cannot attribute acceptance without breaking anonymity — their acceptance is **client-side**. So acceptance
storage splits by principal.

## Decision

Adopt a tenant-terms model, operator-blind, with a **two-tier acceptance gate**.

1. **Operator-set per-locale terms + a version.** Each tenant carries **`terms_markdown_by_locale`** (DE+EN
   markdown) + **`terms_version`** (int, bumped on any edit). The **operator** (tenant owner) assembles/edits the
   terms from a **clause template** — a curated set of ready-made clauses (privacy, rights-to-content,
   prohibited/illegal content incl. **CSAM**, share-links, review-and-removal, external services,
   contact-and-deletion, and an **Impressum** clause) — into their own ToS, and can edit freely. The terms are
   **public operational content** (served, incl. pre-auth on the login page); never E2EE content.

2. **A member acceptance gate — server-side (v1).** An authenticated member must **accept the current
   `terms_version`** before entering; on a version bump the gate re-appears (**re-accept**). Acceptance is
   recorded server-side as **operational metadata** — the member's **`accepted_terms_version`** (+ when). A
   **hard gate**: no entry until accepted.

3. **Anonymous share acceptance — client-side (later slice).** An anonymous public-share visitor sees the tenant
   terms + version and must accept (**hard gate + re-accept** on a version change), but acceptance is stored
   **client-side** (localStorage, per share/version) — the server records **nothing** about an anonymous visitor,
   preserving anonymity. This reuses the same tenant terms (the T&S decryptable-on-report corollary), and is
   deferred.

4. **Operator-blind boundary.** The terms text is public; a member's `accepted_terms_version` is coarse
   operational metadata (like membership/role); the anonymous acceptance is client-only. **No user content, no new
   blindness cost** — recorded in the [security model](../crosscutting/security-model.md).

5. **v1 = member-facing.** The operator sets/edits terms (admin UI, versioned) + the member accept/re-accept
   gate (server-side). The anonymous-share notice (client-side) is a later slice.

## Consequences

- **New operator-visible surface, all public/operational:** the tenant `terms_markdown_by_locale` + `terms_version`
  (public), and per-member `accepted_terms_version` (operational metadata). No content, no key.
- **The terms ride pre-auth** — the login page shows/links them — so they live on the tenant surface reachable
  **before** login (alongside the branding/`/api/setup`-style pre-auth data) and on an **authed tenant endpoint**
  for editing + a member **accept** endpoint.
- **The clause template gives a legally-meaningful starting ToS** (esp. the CSAM/illegal-content + review-removal
  clauses) matching the pre-E2EE product; self-hosted operators own their terms, the central infra ships a
  default. DE strings follow [cc.inclusive-language](../crosscutting/cc.inclusive-language.md).
- **Anonymous client-side acceptance is honest about its limits** (no server non-repudiation for an accountless
  visitor) — a stated property of the deferred share slice.
- **Ties into the T&S "decryptable-on-report" corollary** — the terms are where the operator states that a
  reported **public** share is decryptable-on-report, and the illegal-content/review-removal policy that governs it.

## Alternatives considered

- **Server-side acceptance for anonymous share visitors too.** Rejected — attributing acceptance to an accountless
  visitor needs a cookie/fingerprint that either **breaks anonymity** or is unreliable; client-side localStorage
  is the honest choice for an accountless visitor.
- **Shown-only (no acceptance gate).** Rejected — no consent record; a **hard gate + re-accept on change** is the
  legally-meaningful choice, matching the pre-E2EE product.
- **Free-text markdown (no clause template).** Rejected — the template gives operators a sound starting ToS (the
  CSAM/illegal + review-removal clauses matter) and matches the pre-E2EE UX; the operator can still edit freely.
- **One global ToS (central infra only).** Rejected — veyq is **self-hostable**; each operator sets their own; the
  central infra is one tenant among many.
