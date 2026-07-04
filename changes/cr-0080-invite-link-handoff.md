---
id: cr-0080
title: Invite-link handoff — copy the invite link, no email infrastructure required (member + owner invites)
status: done
satisfies: [uc.spaces.add-member, uc.admin.create-tenant]
adrs: ["0020", "0006"]
invariants: [operator-blind-e2ee]
---

## Goal

Make invites actually deliverable in the **default self-hosted** deployment. The mailer is a dev/log stub —
`TenantInviteMailer` deliberately does **not** send real email (SMTP is "an infra/runbook concern"), and in
production it logs a **secret-free** line. So today an owner mints an invite and the invitee receives *nothing*:
both **member invites** (cr-0077) and **owner invites** (cr-0009/cr-0010) are undeliverable without SMTP.

This closes that gap without any mail infrastructure: the mint/create/resend/reassign responses **return the
raw invite secret to the (owner-gated) caller**, and the UI shows a **copyable invite link** the owner hands
off through their own channel. Directly serves the **self-hostable-by-default** invariant.

## Context & constraints

- **The secret is an admission token, not a key** ([ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md),
  [ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)). Returning it to the **minting owner**
  (already authenticated, owner-gated, over TLS) is exactly the "hand it off out-of-band" step — the same secret
  the mailer would have sent. It still rides the URL **fragment** on the invitee side and is matched server-side
  by **hash only** (never stored plaintext, never echoed in the tenant *list* — only at mint time).
- **No key material, operator-blind untouched:** the invite carries no scope key; the server relays only the
  admission token + already-visible metadata.
- **Link shape:** `<origin>/tenant/accept#<bootstrapSecret>`. A member invites into the tenant they're already
  in (same host → current origin); a Server Owner creates a tenant on **another** host (→ that tenant's domain,
  current protocol).
- **Verify:** `./gradlew -p backend check` + `cd web && npm run check`; the three doc validators.

## Phases

- [x] 1 contracts — add `bootstrapSecret` to the mint responses: `MemberInviteState`, `TenantCreated`,
  `TenantInviteState` (a shared `InviteBootstrapSecret` schema documenting it as the copyable admission token).
  · Done when: contracts lint green; generated clients build backend + web.
- [x] 2 backend — thread the raw `Tokens.random()` from the handler into `CreateTenantOutcome.Created` +
  `InviteView` (create / resend·reassign / member invite) → into the DTOs. Test: a minted **member invite's**
  returned secret is the *real* one — registering with it joins the tenant grant-less
  (`MemberAddSliceTest.mintedMemberInviteReturnsAUsableBootstrapSecretForCopyLinkHandoff`). · Done when:
  `./gradlew -p backend check` green.
- [x] 3 web — a shared `buildInviteLink(secret, domain?)` + clipboard helper (`src/shared/inviteLink.ts`); a
  copy-link success view in the member `InviteMemberDialog`, the owner `CreateTenantDialog`, and the
  resend/reassign edit dialog in `TenantAdminPanel` (each stays open on success showing the link to copy).
  i18n EN+DE. · Done when: `npm run check` green (helper + dialog tests updated).
- [x] 4 docs/coverage — an `invite-link-handoff` acceptance criterion on **both** use-cases
  ([uc.spaces.add-member](../docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml),
  [uc.admin.create-tenant](../docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml)) mapped to the
  new tests; the use-case docs note the copyable-link channel. · Done when: `validate_coverage.py`,
  `validate_changes.py`, `validate_doc_links.py` green.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Return the invite secret + a copyable invite link (member + owner) | Status |
|---|---|---|
| backend | `bootstrapSecret` on the mint/create/resend responses | ☑ this CR |
| web | copy-link success view in the member + owner invite dialogs | ☑ this CR |
| apple / android | native copy-link on their invite screens | ◻ **deferred (web-first; own CRs)** |

## Definition of done

Minting a member or owner invite returns the raw bootstrap secret to the owner-gated caller, and the UI offers a
copyable invite link — so onboarding works with **no email/SMTP** (self-hostable by default). The secret stays
an admission token (never a key, never in the tenant list, matched by hash), operator-blindness untouched.
`./gradlew -p backend check` + `npm run check` green; both invite use-cases carry the `invite-link-handoff`
criterion; the three validators green. Surfacing a pending-invite list + resend on the *member* side (mirroring
the owner-invite management) is a later CR.

## Retrospective

Small, high-leverage: it turns two already-built flows (member-add, tenant creation) from "silently
undeliverable without SMTP" into "works out of the box." The whole change is *returning a value that already
existed* (the raw token, previously handed only to the stub mailer) plus a copy-link view — no new crypto, no
new endpoint. The link is built client-side so the backend never has to guess scheme/host across proxies:
same-host for a member (current origin), the tenant's own domain for an owner invite. A pending-invite list +
member-side resend is the natural next slice (the owner-invite side already has resend/reassign/revoke).
