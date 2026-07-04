---
id: cr-0077
title: Add a member to the shared Tenant space — invite + onboard (grant delivery via cr-0076)
status: planned
satisfies: [uc.spaces.add-member]
adrs: ["0020", "0021", "0007", "0006"]
invariants: [operator-blind-e2ee]
---

## Goal

Realise [uc.spaces.add-member](../docs/features/spaces/use-cases/uc.spaces.add-member.md): let a tenant **owner**
add another person to the shared Tenant space. This CR builds the **human-facing half** — the **MEMBER invite**,
the invitee's **cold-birth registration** as a regular tenant member (→ a grant-less membership), the owner's
**"add member"** UI, and the **"awaiting access"** surfacing — while the **key delivery** (wrapping `K_SC` to the
new member) rides the `grant-delivery` job built in **[cr-0076](cr-0076-background-task-mechanism.md)**. Together
they turn cr-0075's owner-only Tenant space into a genuinely multi-member one.

**Admission is owner-gated; delivery is any key-holder, automatic, first-online-wins** ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)).
Authenticity is **Plain** in v1 (the fingerprint/TOFU seam is documented, not built). **Depends on
[cr-0076](cr-0076-background-task-mechanism.md).** Groups member-add, revocation/rotation, and native heads are
**explicit non-goals**.

## Context & constraints

- **Reuse the owner-invite machinery.** The MEMBER invite extends the tenant-owner invite
  ([cr-0009](cr-0009-create-tenant-structural.md) / [cr-0010](cr-0010-tenant-activation.md)) with a **role** — a
  single-use secret, hashed server-side, delivered out-of-band. Cold-birth registration extends `registerAndAccept`
  to a non-owner MEMBER; the invitee's identity is **born on-device** and only its **public** bundle uploaded.
- **A grant-less member is a first-class, already-handled state.** Role without a grant is the hollow membership
  ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)); the member lands in the **Personal
  fallback** already rendered by cr-0075 ([en.spaces.tenant-scope](../docs/features/spaces/enablers/en.spaces.tenant-scope.md)).
  Creating it **triggers cr-0076's `grant-delivery` origination**; this CR does not wrap keys itself.
- **Operator-blind.** The server sees the invite metadata, the member's identity **public** key, and opaque
  grant ciphertext — never `K_SC`. The owner's **member directory** returns only operational metadata
  (memberships + identity pubkeys + grant-present flags), never a key.
- **The owner is not a bottleneck.** Because delivery is any key-holder (cr-0076), "awaiting access" lasts only
  until *any* member is next online — not specifically the owner.
- **Web-first** ([ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)); native invite/
  directory UI is deferred to its own CRs.
- **Verify:** `./gradlew -p backend check` + the `http/` signed happy-path + `cd web && npm run check`; a **live
  browser smoke** (Phase 6).

## Phases

- [ ] 1 contracts — extend the tenant invite with a **`role` (MEMBER)** on mint + accept/register; add a
  **member directory** read (`GET /api/scopes/{scopeId}/members` → memberships + identity pubkey + grant-present)
  for the owner UI. · Done when: contracts lint green; generated clients build on backend + web.
- [ ] 2 backend — mint a **MEMBER invite** (owner-gated); **cold-birth registration** as a regular MEMBER (extend
  `registerAndAccept` with the role) → a **grant-less membership** (which cr-0076 picks up as a `grant-delivery`
  job); the **member directory** query (owner-gated). · Done when: `./gradlew -p backend check` green; tests
  cover an owner minting a MEMBER invite, an invitee registering as a grant-less MEMBER, the `grant-delivery` job
  being enqueued, and the directory listing pending members.
- [ ] 3 http — a signed end-to-end (`http/spaces/add-member.http`): owner invites (MEMBER) → invitee registers →
  grant-less membership + job → (cr-0076 worker) delivers → the member holds a grant. · Done when: the `.http`
  response-handler assertions pass against a fresh instance.
- [ ] 4 web — the owner's **"add member"** UI (invite by email); the **"awaiting access"** surfacing on the
  Tenant entry for a grant-less member (reuses the cr-0075 Personal fallback + an `unavailable`/`pending` flag);
  the owner's **member directory** view. The member reaching the Tenant once granted is already cr-0075 +
  cr-0076's worker. i18n EN+DE for the new labels. · Done when: `npm run check` green; component tests cover the
  invite form and the awaiting-access surfacing.
- [ ] 5 coverage — fill the **onboarding criteria** of
  [uc.spaces.add-member.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml)
  (`member-invite-and-onboard`, `scope-member-directory`, the admission side of `admission-vs-delivery`) with
  backend/http/web refs; with cr-0076's delivery criteria now also filled, **drop `status: planned`**; note
  apple/android as gaps (web-first). · Done when: `validate_coverage.py`, `validate_changes.py`,
  `validate_doc_links.py` green.
- [ ] 6 verify (browser smoke) — live stack: the **owner** invites a second person by email → they register →
  land in **Personal** (Tenant "awaiting access") → a key-holder's client **auto-delivers** the grant (cr-0076,
  first-online-wins) → they now open the shared **Tenant** space. · Done when: confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Invite + onboard a member, surface awaiting-access | Status |
|---|---|---|
| backend / http | MEMBER invite + cold-birth membership + member directory | ◻ this CR |
| web | "add member" UI + awaiting-access + directory view | ◻ this CR |
| apple / android | native invite/directory UI | ◻ **deferred (web-first; own CRs)** |

## Definition of done

A tenant owner invites a person by email; they register their own account (identity born on-device) and land in
the **Personal fallback** as a grant-less member marked *awaiting access*; a key-holder's client then delivers
the Tenant grant automatically (cr-0076, first-online-wins), and the member opens the shared space. Admission is
owner-gated, delivery is any key-holder; the server saw only invite metadata, identity public keys, and opaque
grant ciphertext. `./gradlew -p backend check` + the `http/` happy-path + `npm run check` green;
`uc.spaces.add-member` fully covered (`status: planned` dropped); the three doc validators green; the live smoke
confirmed with screenshots.

## Retrospective

_(to be written at close)_
