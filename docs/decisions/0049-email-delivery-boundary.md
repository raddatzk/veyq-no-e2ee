# ADR 0049 — Sending mail is a send-time metadata + third-party boundary; a mail carries only delivery essentials

- Status: Proposed
- Date: 2026-07-24

## Context

veyq is operator-blind: the server stores only ciphertext + opaque operational metadata
([security-model](../crosscutting/security-model.md)). Introducing a mail-send path
([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) creates two new exposures the model must state
honestly rather than pretend mail is free:

1. **The veyq operator, at send time.** The recipient address is already stored in plaintext — it is the login
   identifier and the source of the deterministic auth salt ([ADR 0003](0003-operator-blind-password-auth.md)).
   But *sending* adds a new access-pattern: the operator learns *who is contacted, when* (invite / verify
   traffic). The account email is, in fact, not yet listed in the security-model's server-visible metadata
   inventory — a pre-existing doc gap (inbox #43) this ADR closes for the email field.
2. **A mail provider — a different, consented boundary.** The SMTP server / relay learns recipient + timing too.
   This is the same shape already drawn for reverse-geocoding ([ADR 0016](0016-geocoding-provider-neutrality.md)):
   "operator-blind" names the **veyq** operator; a chosen external service is a **separate, consented** boundary,
   fenced so it can never widen the veyq operator's view.

## Decision

**A mail carries only what delivery needs, never confidential metadata; and the mail provider is a fenced,
consented third party.**

1. **A mail carries:** the accept/verify **link** (the secret rides the URL **fragment**, never a header or a
   path segment beyond the fragment), the tenant **domain**, an **expiry**, and neutral bilingual (EN + DE) copy.
   **From the tenant's own address** (`noreply@<tenant.domain>` or the configured `from`,
   [ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) — the recipient sees the domain they are invited to,
   and no cross-tenant identity leaks.
2. **A mail carries no confidential metadata:** no scope / group / memory names, no member lists, no real names
   (those are sealed under `K_tenant`), no content — nothing the operator is otherwise blind to. The mail must
   not become a side-channel that leaks what the database does not. An invite says only that *someone* invited
   you to *this domain*.
3. **The SMTP provider is fenced like ADR 0016:** it receives only the recipient address + the message
   (link / domain / expiry) — never a veyq session, user-id, or content. In a **self-hosted** deployment the
   operator runs their own SMTP, so this boundary collapses to the operator themselves (moot, as with the
   self-hosted operator-blind case).
4. **Send-time recipient + coarse timing is conceded operational metadata, like membership** — stated in the
   security-model inventory (which also now lists the account email), never treated as content.

## Consequences

- **Honest model.** The send-time recipient/timing concession and the provider boundary are named, not hidden;
  the security-model metadata inventory gains the account email + the mail send-fact.
- **A hard rule on every mail template, now and later.** Invite mail today; verification and (a future topic)
  recovery mail tomorrow — all bound to *link + domain + expiry + neutral copy only*. The content boundary is a
  candidate for a promoted guard (a check that a mail body references no scope/member field).
- **Self-hosted vs SaaS.** Self-hosted: the provider boundary collapses to the operator. SaaS: the operator's
  chosen relay is a documented third party, feeding the SaaS-ops (#38) and legal (#40) topics.
- **The SMTP credential is now a server-held secret.** Per-tenant SMTP means the operator holds each tenant's
  SMTP password (encrypted at rest, [ADR 0050](0050-operator-config-secret-encryption.md)) — inherent to sending
  on the tenant's behalf; self-hosted, moot.
- **Rejected — richer, friendlier mails** ("You've been invited to the *Smith Family* album by *Alice*"). Warmer,
  but it leaks scope / member / relationship metadata — to the operator's mail logs *and* to the provider — that
  veyq otherwise seals. The friendliness is not worth re-exposing sealed metadata over an unsealed channel.
- **Rejected — proxying mail through the veyq backend to "hide" the provider.** The backend already holds the
  address, and the provider is downstream regardless; proxying buys nothing. The fence is *minimal content + no
  veyq identity to the provider*, not proxying.
- **Rejected — sealing/hashing the address to shrink at-rest exposure as part of this ADR.** Out of scope: the
  address must be plaintext at send time and for login (ADR 0003), and at-rest hardening belongs to the
  multi-ServerOwner / server-scope-key work (inbox #43). This ADR only *documents* the existing plaintext field.
