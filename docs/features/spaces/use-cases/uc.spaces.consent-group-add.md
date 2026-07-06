# UC: Consent before being added to a group

- ID: uc.spaces.consent-group-add
- Primary actor: **the invited member** (accepts or declines) — with the **GroupOwner** as the trigger (adds a
  consent-required member) and **any account holder** as the one who set the preference.
- Goal: let a person **opt in to being asked first** — so a GroupOwner cannot silently drop them into a group.
  When the toggle is on, an add becomes a **proposal** the person **accepts or declines**; the group key `K_SC`
  is delivered **only after acceptance**.
- Preconditions: The target is **already a tenant member** with a published identity public key
  ([en.spaces.member-identity](../enablers/en.spaces.member-identity.md)). The target has turned **"ask before
  adding me to groups"** on (an **account-global** preference, default **off**). The GroupOwner holds the group
  grant (so their device can read the group name and seal it).
- Postconditions (success on **Accept**): the proposal becomes a normal **grant-less** membership; grant-delivery
  wraps `K_SC` to the newcomer (the usual *awaiting access* window, [uc.spaces.add-group-member](uc.spaces.add-group-member.md)),
  and the group opens for them. On **Decline**: the soft membership is dropped — **nothing was ever wrapped**, no
  trace kept. Operator-blind throughout: the server saw a membership row, identity public keys, and opaque
  ciphertext (the sealed name, then the grant) — never `K_SC` and never the plaintext name.

This use-case realizes the **optional consent opt-in** of [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §5.
It is **consent-first, never wrap-then-soft**: `K_SC` is gated **before** the wrap, because a delivered key can't
be un-given. It builds directly on [uc.spaces.add-group-member](uc.spaces.add-group-member.md) (the default
direct-add) — the only additions are the **soft membership**, a **one-off name-seal** so the invitee can decide
informed, and the **accept/decline** step.

## Main flow

1. **The preference (account-global).** A person turns on **"ask before adding me to groups"** — one setting on
   their account, applying in **every** tenant they belong to. It is a low-sensitivity server-visible boolean
   (operational metadata, like other account settings): the operator learns *that* someone likes to be asked,
   never any key or content. Default **off** → the direct-add of [uc.spaces.add-group-member](uc.spaces.add-group-member.md).
2. **The owner adds (proposal, not admission).** The GroupOwner resolves the target by **username** exactly as in
   direct-add and picks them. The server sees the preference is **on**, so instead of a grant-less membership it
   records a **soft, grant-less membership** (`pendingConsent`) — *proposed*, not yet a member. **grant-delivery
   skips soft memberships**, so no key-holder ever wraps `K_SC` to a pending invitee.
3. **The one-off name-seal.** The owner's device — a key-holder — seals **just the group name** to the invitee's
   identity public key: `asymWrap(hybridPublic(invitee), name, "group-name")`, posted as the proposal's sealed
   name. No standing second key, no rotation burden — a **single** ciphertext, readable only by the invitee, so
   they can see *what* they are being asked to join. The group's real name stays sealed under `K_SC` as always.
4. **The invitee decides.** In a **"group invitations"** view the invitee unwraps the sealed name with their own
   identity private key and sees **the group's name + who added them** (the owner's per-tenant username, already
   visible to them) + the tenant.
   - **Accept** → the server clears `pendingConsent`; the proposal is now a normal grant-less membership, and the
     ordinary directory-driven grant-delivery wraps `K_SC` to them (first-online-wins, *awaiting access* →
     Active). From here it is identical to direct-add step 3+.
   - **Decline** → the server **deletes** the soft membership. Nothing was wrapped; no `declined` state is kept
     (silent drop). The owner may propose again later.
5. **Rescind (owner).** Before acceptance the owner may withdraw the proposal — the same silent delete. After
   acceptance the person is a real member and leaves via the normal remove path
   ([uc.spaces.manage-group-members](uc.spaces.manage-group-members.md)).

```plantuml
@startuml
title Consent to a group add — gate K_SC BEFORE the wrap (server never holds K_SC or the name)
skinparam shadowing false
actor "GroupOwner\n(key-holder)" as O
participant "Server\n(blind)" as S
actor "Invitee\n(consent = on)" as I
participant "Client\n(invitee)" as CI
actor "Any key-holder\n(online)" as M

O -> S : resolve username → { userId, identityPublicKey }
O -> S : add → server sees consent=on → record SOFT membership (pendingConsent)
note right of S : grant-delivery SKIPS soft memberships\n→ no wrap of K_SC yet
O -> O : seal group name: asymWrap(inviteePub, name, "group-name")
O -> S : POST proposal's sealed name (opaque ciphertext)
I -> CI : open invitations → asymUnwrap sealed name → see name + who added
alt Accept
  I -> S : accept → clear pendingConsent (now a normal grant-less member)
  M -> M : (next online) grant = asymWrap(inviteePub, K_SC, "scope-grant") — first-online-wins
  M -> S : POST grant (opaque) → awaiting access → Active
else Decline
  I -> S : decline → delete soft membership (nothing was ever wrapped)
end
@enduml
```

## Alternate / exception flows

- **A1 — the target has consent OFF (the default):** there is no proposal step — the add is the immediate
  direct-add of [uc.spaces.add-group-member](uc.spaces.add-group-member.md). This use-case does not apply.
- **A2 — already a member (or already proposed):** picking them is a **no-op** (idempotent) — a live membership
  or an open proposal already exists; no duplicate.
- **A3 — the invitee is offline / never responds:** the proposal simply **stays pending** — not an error, no key
  at risk (nothing is wrapped). The owner may rescind; there is no auto-expiry in v1 (a deferred polish).
- **A4 — re-invite after a decline:** allowed. The decline left no `declined` marker (silent drop), so a fresh
  proposal is just a new soft membership. (The cost, accepted: an owner could re-propose repeatedly — a
  rate-limit/mute is a deferred polish, not a v1 gate.)
- **E1 — the username doesn't resolve in this tenant:** a generic *not-found*, tenant-scoped resolve — identical
  to direct-add E1.
- **E2 — key-substitution by a malicious operator:** the name-seal (and later the grant) wraps to the pubkey the
  server returns — **v1 is *Plain*** (no TOFU-pin, no hard-gate), consistent with direct-add E2 and the shared
  primitive. Consent gates the *human's* agreement, not key authenticity; the fingerprint / safety-number remains
  the explicit hardening seam ([security-model](../../../crosscutting/security-model.md), [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)).

## Quality notes

- **Consent-first, never wrap-then-soft.** The gate is **before** the wrap: grant-delivery skips soft memberships,
  so `K_SC` never reaches someone who hasn't accepted. Deleting an already-delivered wrap would not un-give the
  key — so we never deliver it in the first place ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §5).
- **Operator-blind — no new leak beyond direct-add.** The server stores a membership row (`pendingConsent`) + one
  opaque sealed-name ciphertext, and relays identity public keys. It already stores membership rows + sealed names
  for direct-add; the plaintext name and `K_SC` never appear. The **consent boolean is account-global and
  server-visible** — a deliberate, honest exception: a GroupOwner attempting an add learns *that* the person wants
  to be asked (the add returns "proposal sent" instead of "added"). Low-sensitivity operational metadata, no key
  or content — the standing trade for a one-setting preference over a per-tenant one.
- **Pending is owner-visible only.** A `pendingConsent` invitee shows in the group's member directory as *invited
  — awaiting consent* to **owners** only; ordinary members see them only once they accept. The invitee's own
  invitations view is theirs alone.
- **Additive, reversible cheaply.** Accept flips one flag → the existing grant-delivery does the rest; Decline /
  Rescind is a single row delete. No rotation, no re-seal, no second standing key.
- **Consent's strength tracks the client tier ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).** The
  gate is enforced by the key-holders' clients not wrapping `K_SC` to a soft member — so it is a real, crypto-grade
  guarantee on a **trusted** client (native / trusted-web: an active operator can't make it wrap), and
  **honest-operator-only** on operator-served web (a tampered client could wrap regardless) — the same tier as all
  veyq E2EE, no consent-specific weakness.

## What this means for you / Was das für dich bedeutet

**EN —** By default, a friend can add you to a shared group and you'll just find yourself in it — quick, but not
everyone wants that. So there's a switch: **"ask before adding me to groups."** Turn it on and an add becomes an
invitation instead. You'll see the group's **name** and **who added you** — sealed to you alone, so even the server
can't read it — and you decide: **Accept** and the group's key is handed to you the usual way; **Decline** and it's
as if it never happened — nothing was shared with you, and no record of the "no" is kept. Until you accept, the key
to the group is **never** handed out, so nothing of the group ever reaches you without your say-so.

**DE —** Standardmäßig kann dich ein:e Freund:in zu einer geteilten Gruppe hinzufügen, und du findest dich einfach
darin wieder — schnell, aber nicht jede:r will das. Darum gibt es einen Schalter: **„frag mich, bevor du mich zu
Gruppen hinzufügst."** Ist er an, wird ein Hinzufügen zu einer Einladung. Du siehst den **Namen** der Gruppe und
**wer dich hinzugefügt hat** — nur an dich versiegelt, sodass nicht einmal der Server es lesen kann — und du
entscheidest: **Annehmen**, und der Schlüssel zur Gruppe wird dir wie üblich übergeben; **Ablehnen**, und es ist,
als wäre es nie passiert — nichts wurde mit dir geteilt, und das „Nein" wird nirgends festgehalten. Bis du annimmst,
wird der Schlüssel zur Gruppe **nie** herausgegeben — nichts aus der Gruppe erreicht dich ohne dein Einverständnis.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Use-cases: [uc.spaces.add-group-member](uc.spaces.add-group-member.md) (the default direct-add this gates),
  [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (how an accepted member later leaves)
- Enablers: [en.spaces.group-scope](../enablers/en.spaces.group-scope.md) (the group + its sealed name),
  [en.spaces.member-identity](../enablers/en.spaces.member-identity.md) (the identity pubkey the name is sealed to)
- Decisions: [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §5 (the consent opt-in — consent-first,
  one-off name-seal), [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) (the grant-wrap +
  Plain-authenticity seam), [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) (grant-delivery,
  which skips soft memberships), [ADR 0023](../../../decisions/0023-per-tenant-member-identity.md) (per-tenant username),
  [ADR 0026](../../../decisions/0026-client-trust-tiers.md) (the client trust tier the consent guarantee rides on)
