---
id: cr-0094
title: Consent opt-in for group-add — soft membership + one-off name-seal (G2b)
status: planned
satisfies: [uc.spaces.consent-group-add]
adrs: ["0014", "0020", "0021", "0023", "0009", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the **optional consent opt-in** for group-add ([uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md),
[ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §5). Today a GroupOwner **direct-adds** an
existing tenant member (cr-0086) and grant-delivery hands them `K_SC` — no say from the added person. G2b lets a
person turn on an **account-global "ask before adding me to groups"** preference so an add becomes a **proposal**
they **accept or decline**. It is **consent-first, never wrap-then-soft**: the person is held as a **soft
(`pendingConsent`) grant-less membership** that **grant-delivery skips**, so `K_SC` is gated **before** the wrap;
the owner's device **one-off-seals just the group name** to the invitee so they decide informed. **Accept** →
ordinary grant-delivery (the usual *awaiting access* window); **Decline** → silent drop, nothing was wrapped.

## Context & constraints

- Durable truth: [uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md)
  (the refined target picture — the flow, the states, the decisions), [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md)
  §5 (consent-first, one-off name-seal, default direct-add), [ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)
  (the grant-wrap + Plain authenticity), [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)
  (grant-delivery, which must now skip soft memberships), [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)
  (per-tenant username / identity pubkey the name is sealed to), [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)
  (the conformance-vector discipline the new `group-name` context is pinned by, so native stays byte-identical).
- Builds directly on **cr-0086** (direct-add: username-resolve + grant-less membership) and reuses **cr-0076/0079**
  (grant-delivery) unchanged except for the **skip**; reuses **cr-0093**'s epoch-aware complete (a delivered
  grant records `grantedEpoch = currentEpoch`).
- **Decisions carried from the refine (not re-litigated here):**
  - The toggle is **account-global** — a server-visible boolean on `veyq_user` (default off). A GroupOwner
    attempting an add learns *that* the target wants to be asked (the resolve/add exposes `requiresConsent`). This
    is the **one honest exception**: low-sensitivity operational metadata, never a key or content — the standing
    trade for a single preference over a per-tenant one.
  - **Decline is a silent drop** (no `declined` state kept — no extra server-visible metadata); **re-invite is
    allowed**. **Rescind** by an owner is the same delete. Neither rotates (nothing was ever granted — distinct
    from the rotating remove of a *real* member, cr-0089).
  - **Pending is owner-visible only** in the member directory.
  - Authenticity stays **Plain** (the fingerprint/TOFU seam is unchanged).
- The **name-seal is owner-synchronous** — the adder is definitionally a key-holder (only an owner admits, ADR
  0014 §4), so their device reads the group name and seals it at add-time; no task-queue, no second standing key.
- Invariants at risk: **operator-blind preserved.** New server-visible surface = the `pendingConsent` flag + one
  opaque `sealedInviteName` ciphertext + `addedBy` + the account boolean — all operational metadata or ciphertext;
  `K_SC` and the plaintext name never appear. Forward secrecy untouched (a soft member is never keyed).

## Phases

- [ ] 1 contracts — crypto: add the **`group-name`** AAD context + a **vector case** for `asymWrap(inviteePub,
  name, "group-name")` (keeps native byte-identical). API: `requireGroupAddConsent` boolean on the account profile
  (`getMyProfile`/`updateMyProfile`); the group member-resolve/add surfaces the target's **`requiresConsent`**;
  `addGroupMember` accepts an optional **`sealedInviteName`**; **`pendingConsent`** on the directory row
  (owner-visible); **accept** / **decline** endpoints (invitee-gated) + a **rescind** (owner-gated, or reuse
  member-remove for a soft row); an **invitations list** for the caller's pending proposals (scopeId + decrypted-
  by-client name + `addedBy`). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [ ] 2 backend — migration: `pending_consent` + `sealed_invite_name` + `added_by` on membership;
  `require_group_add_consent` on `veyq_user`. `addGroupMember`: consult the target's flag → record a
  **`pendingConsent`** soft membership + store `sealedInviteName` + `addedBy` (else the cr-0086 direct-add; a
  `requiresConsent` target with no `sealedInviteName` → 400). **grant-delivery reconcile + complete skip
  `pendingConsent`** (never wrap `K_SC` to a soft member). **accept** (invitee-gated): clear `pendingConsent` →
  a normal grant-less membership grant-delivery now picks up. **decline** (invitee) + **rescind** (owner): delete
  the soft row, **no rotation**. Directory: `pendingConsent` visible to **owners only**. Profile get/set the flag.
  Slice tests: consent-on add records a soft row + skips delivery; accept flips → delivered; decline/rescind
  deletes with no rotation; a soft member is invisible to non-owners; consent-off is unchanged direct-add. · Done
  when: `./gradlew test` green.
- [ ] 3 web — the account **consent toggle** (get/set); the owner's **add flow** (resolve → if `requiresConsent`,
  seal the group name with the `group-name` context → submit the proposal → "invitation sent, awaiting consent");
  the invitee's **"group invitations"** view (list → `asymUnwrap` the sealed name → **Accept** / **Decline**); the
  directory shows pending (owner-only). Reuses the members/spaces data layer. · Done when: `npm run check` green +
  live: A (consent on) is proposed by owner B → A sees the group name + adder, **Accept** → `K_SC` delivered → A
  browses; a second run **Decline** → nothing delivered, row gone.
- [ ] 4 http — accept/decline unauth + the consent-gated add boundary (the crypto happy-path is a signed
  key-holder flow → slice/live covered). · Done when: the unauth assertions hold headless.
- [ ] 5 coverage — flip [uc.spaces.consent-group-add.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.consent-group-add.yaml)
  **planned → implemented** with the backend `…SliceTest` refs + the web tests + the `http` boundary; refresh the
  gaps (native still deferred). · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A person can turn on **"ask before adding me to groups"** (account-global). With it on, a GroupOwner's add becomes
a **proposal**: a `pendingConsent` soft membership grant-delivery **skips** + a one-off name-seal, so `K_SC` is
gated **before** the wrap. **Accept** delivers the key the usual way; **Decline**/**Rescind** drops the soft row
with no trace and no rotation. Pending is owner-visible only; authenticity stays Plain. Operator-blind (opaque
sealed name + a boolean + a flag; no key, no plaintext name). Coverage + guards green. **Out of scope:** proposal
**auto-expiry**; a **re-invite rate-limit / mute**; **push notifications** (a poll/badge like grant-delivery is a
later polish); the fingerprint/TOFU hardening (the standing seam); native (apple/android) parity — web-first.

## Notes

- **Why owner-synchronous, not a task.** Grant-delivery needs *any* key-holder (the adder may not hold the key for
  the *tenant* case). For a **group** add only an **owner** admits (ADR 0014 §4), and an owner **is** a key-holder —
  so the name-seal happens on their device at add-time, no queue needed. The one asynchronous step remains the
  post-accept `K_SC` delivery, which is the existing grant-delivery job (first-online-wins).
- **The skip is one predicate.** grant-delivery already targets "behind" memberships (grant-less or stale,
  cr-0093); this adds `and pending_consent = false` so a soft member is never a delivery target until Accept
  clears the flag. Small, and it keeps the "never wrap before consent" invariant in one place.
- **`group-name` vs `scope-grant`.** A distinct AAD context so a name-seal ciphertext can never be confused with a
  key-grant ciphertext (domain separation) — the invitee unwraps a *name*, never a key.
- **Client-tier honesty ([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)).** The consent gate (key-holders'
  clients not wrapping `K_SC` to a soft member) is crypto-grade on a **trusted** client (native / trusted-web) and
  **honest-operator-only** on operator-served web — the same tier as all veyq E2EE. The UI should label the tier,
  not imply operator-proof consent on web.
