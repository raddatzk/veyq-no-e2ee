---
id: cr-0077
title: Add a member to the shared Tenant space — invite + onboard (grant delivery via cr-0076)
status: done
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

- [x] 1 contracts — extend the tenant invite with a **`role` (MEMBER)** on mint + accept/register; add a
  **member directory** read (`GET /api/scopes/{scopeId}/members` → memberships + identity pubkey + grant-present)
  for the owner UI. · Done when: contracts lint green; generated clients build on backend + web.
- [x] 2 backend — mint a **MEMBER invite** (owner-gated); **cold-birth registration** as a regular MEMBER (extend
  `registerAndAccept` with the role) → a **grant-less membership** (which cr-0076 picks up as a `grant-delivery`
  job); the **member directory** query (owner-gated). · Done when: `./gradlew -p backend check` green; tests
  cover an owner minting a MEMBER invite, an invitee registering as a grant-less MEMBER, the `grant-delivery` job
  being enqueued, and the directory listing pending members.
- [~] 3 http — **deferred by request.** A bare `.http` cannot cold-birth a real MEMBER (the ML-KEM identity +
  the scope-grant wrap need the client crypto suite the `_lib` signing harness lacks — the same wall cr-0076 and
  `content.http` hit). The full owner-invites → invitee-registers → grant-less-membership → delivered loop is
  proven end-to-end at the integration layer (`MemberAddSliceTest` + `TasksSliceTest`, over the HTTP stack). A
  scripted `add-member.http` follows if/when the harness gains an ML-KEM wrap.
- [x] 4 web — the owner's **"add member"** UI (invite by email); the **"awaiting access"** surfacing on the
  Tenant entry for a grant-less member (reuses the cr-0075 Personal fallback + an `unavailable`/`pending` flag);
  the owner's **member directory** view. The member reaching the Tenant once granted is already cr-0075 +
  cr-0076's worker. i18n EN+DE for the new labels. · Done when: `npm run check` green; component tests cover the
  invite form and the awaiting-access surfacing.
- [x] 5 coverage — fill the **onboarding criteria** of
  [uc.spaces.add-member.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml)
  (`member-invite-and-onboard`, `scope-member-directory`, the admission side of `admission-vs-delivery`) with
  backend/http/web refs; with cr-0076's delivery criteria now also filled, **drop `status: planned`**; note
  apple/android as gaps (web-first). · Done when: `validate_coverage.py`, `validate_changes.py`,
  `validate_doc_links.py` green.
- [x] 6 verify (browser smoke) — live stack: the **owner** invites a second person by email → they register →
  land in **Personal** (Tenant "awaiting access") → a key-holder's client **auto-delivers** the grant (cr-0076,
  first-online-wins) → they now open the shared **Tenant** space. · Done when: confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Invite + onboard a member, surface awaiting-access | Status |
|---|---|---|
| backend / http | MEMBER invite + cold-birth membership + member directory | ☑ backend (http e2e deferred) |
| web | "add member" UI + awaiting-access + directory view | ☑ this CR |
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

Built as specified; the human-facing half of member-add now rides cr-0076's delivery mechanism end-to-end.

- **Endpoint split honours the tenant-vs-group axis.** The MEMBER invite is its own tenant-pathed, tenant-owner-
  gated endpoint (`POST /api/tenants/{tenantId}/member-invites`) — deliberately **not** folded into create-tenant
  (Server-Owner) — so the later *group* member-add stays a distinct scope-based `POST …/members` over an existing
  tenant member (no email onboarding). The member **directory** (`GET /api/scopes/{scopeId}/members`) is scope-
  generic and reused for both. Admission is owner-gated; the directory is any key-holder's (a uniform 403 for a
  non-member also avoids scope enumeration).
- **Accept is role-branched, not duplicated.** `accept` / `registerAndAccept` read the invite's role: OWNER
  cold-births the scope + requires a grant (unchanged); MEMBER joins the ACTIVE tenant **grant-less** (no scope
  birth, no grant), which cr-0076 reconciles into a `grant-delivery` job. One correctness fix fell out: the
  account/email checks now use the **invite's** email, not the tenant owner's — they differ for a member invite.
- **The worker got wired in.** cr-0076 built `runGrantDeliveryWorker` but never triggered it; `enterScope` now
  fires it (best-effort) on entering a shared scope, so any key-holder delivers automatically on next entry —
  the load-bearing glue for first-online-wins.
- **Live smoke passed end-to-end** (Phase 6): owner logs in → **Members** panel (directory + owner-gated invite)
  → invites `partner@veyq.test` (backend mints the MEMBER invite) → the invitee opens the link and **cold-
  registers grant-less** (DB: `MEMBER, grant-less`) → the owner re-enters the Tenant and their client **auto-
  delivers** (DB: `DELIVERED`, job `02f271ff DONE`) → the owner's directory shows the member **Aktiv** → the
  member logs in and **opens the shared Tenant space** (no awaiting-access). Screenshots captured. `./gradlew -p
  backend check` + `npm run check` green; the three validators green.

**Deviations (honest):**
- **Phase 3 (http) deferred by request** — and blocked anyway by the same ML-KEM wall cr-0076 hit (a bare `.http`
  can't cold-birth a real member). The full loop is proven by `MemberAddSliceTest` + `TasksSliceTest` over the
  HTTP stack, and now by the live browser smoke.
- **Making the accept flow role-aware surfaced a copy gap, now closed:** the join page's crypto path was fixed
  for a MEMBER (no grant), and its *copy* — which had read owner-flavoured ("take ownership") — is now role-aware
  too. A `jcopy(base)` helper in `TenantJoinView` picks the `<base>Member` i18n variant when the invite role is
  MEMBER (EN+DE added for invitedTo / registerSub / join / signinSub / signinReadySub / done / doneNote), so a
  member reads "join the shared space" and that access is delivered once a member is online.
