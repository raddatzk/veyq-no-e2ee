# Scopes & Tenancy

> The structural layer: **where content lives and who may access it.** It defines the scope kinds, the
> organizational structures (tenant / group / personal), how access is granted *cryptographically*
> (scope keys, grants, shares), how a new member is onboarded (the invite-wrap), and the unified
> navigation contract. Engineering sibling to the [Security Model](security-model.md) and the
> [Account & Key Model](account-key-model.md); byte formats live in the crypto wire-format spec.

This is the **target model**; first-build scope is tracked via the inbox and change-requests.

## Scope — the unit content is keyed on

A **scope** is the unit business data is keyed on, addressed by an **opaque scope ID (UUID)**, never by
name. A **tenant** is the top-level container and carries the domain; the other kinds live **within** a
tenant:

- **`TENANT`** — the tenant's shared root scope. One server hosts many tenants, each with its own domain.
- **`GROUP`** — a collaborative space within a tenant, **no nesting**; all members have equal content
  rights, admins manage membership and settings only.
- **`PERSONAL`** — a member's private space **within a tenant** (one per member, per tenant), created
  when the user joins that tenant. Maximum confidentiality; no other key-holder unless the member
  explicitly shares.

**Users are global to the server** and may belong to multiple tenants; a user therefore has a **separate
personal scope per tenant**. Because every scope lives within a tenant, it is reached under that
tenant's host — so sharing a private memory yields a link on the **right** domain (a family memory
shared from your personal scope appears under `family.example.com`, never `friends.example.com`). See
[ADR 0002](../decisions/0002-personal-scope-per-tenant.md). The same navigation and key rules apply to
all kinds — the kind changes *who holds keys*, not the model.

## Tenant resolution — by Host

A tenant carries a **domain**. The reverse proxy forwards the request (with its `Host`), and the
backend resolves **which tenant** is meant from that `Host` (a host → tenant mapping). The tenant
context therefore comes from the domain, not from a tenant id or name in the path; **business data
within the resolved context is still addressed by opaque scope/node IDs**. Tenant↔domain is
server-visible operational metadata, so this does not affect operator-blindness.

Every scope — including personal and group — lives **within** a tenant, so there is no host-less scope:
each is reached under its tenant's host like everything else. This is what keeps share links on the
correct domain (see the scope kinds above and [ADR 0002](../decisions/0002-personal-scope-per-tenant.md)).

## How access works — keys, grants, shares

```plantuml
@startuml
title Scope access — keys, grants, shares
skinparam shadowing false
skinparam rectangle {
  BackgroundColor #FAFAFA
  BorderColor #888888
}

rectangle "Member A\nidentity key" as ma
rectangle "Member B\nidentity key" as mb
rectangle "Scope private key" as spk
rectangle "Per-subtree content keys" as ck
rectangle "Content\n(nodes, media, journal, metadata)" as content
rectangle "Public share\n(per-share key)" as share

ma --> spk : unwraps (holds a grant)
mb --> spk : unwraps (holds a grant)
spk --> ck : unwraps
ck  --> content : encrypts
ck  --> share : one subtree key wrapped\nto a per-share key (token-unlocked)

note right of spk
  A grant = the scope private key wrapped to a
  member's identity public key (one wrap = the
  whole scope). Each member unwraps it with their
  own identity private key.
end note
@enduml
```

- **Grant = the unit of access.** A member receives the **scope private key wrapped to their identity
  public key** — one wrap grants the whole scope. Membership *is* this cryptographic grant; the server
  stores that a grant exists, not a readable role that confers content.
- **Shares are least-privilege.** A public share gets only the *specific* subtree's content key wrapped
  to a per-share key, unlocked by the share token — never the scope key. (Detail in the sharing feature.)
- **Group/tenant roles** live in membership rows (e.g. group admin, tenant admin) and gate *management*
  actions, not content readability — content readability is purely the grant.

## Authorization vs. confidentiality — two enforcement layers

This separation is the heart of the model:

- **The server enforces *authorization*:** a valid grant + `ACTIVE` membership, checked by small guards
  (e.g. a scope-access guard, a tenant-admin guard) on **opaque IDs**. The server never reads content to
  decide.
- **The client enforces *confidentiality*:** only the grant unwraps the scope key, so a member can read
  a scope **only** if they hold a grant for it. *Visibility is cryptographic*, not a server promise.

## Onboarding a new member — the invite-wrap

A new member has **no identity keypair yet**, so the scope key cannot be wrapped to them in advance.
Access is **always an explicit, informed act by the granting owner** (the only way confidentiality is
lowered). The mental model: **one passenger, two envelopes** — the *passenger* is the scope private key;
a throwaway *invite-wrapping keypair* is the envelope in between.

```plantuml
@startuml
title Invite-wrap — one passenger (scope private key), two envelopes
skinparam shadowing false
actor "Owner\n(holds scope key)" as O
participant "Server\n(blind)" as S
actor "Invitee\n(no keypair yet)" as I

O -> O : unwrap scope private key (the passenger)
O -> O : generate throwaway invite-wrapping keypair
O -> O : seal passenger to invite-wrapping public key
O -> S : POST invite { inviteWrappingPublicKey, wrappedScopePrivateKey }
note right of S : stores both, opens neither\n(envelope key absent)
S --> O : { inviteId, bootstrapSecret }
O ->> I : link  ( #fragment = invite-wrapping private key, never sent )
I -> S : fetch material + bootstrapSecret (possession proof)
S --> I : { inviteWrappingPublicKey, wrappedScopePrivateKey }
I -> I : open envelope with fragment key -> passenger
I -> I : generate own identity; re-seal passenger to own identity key
I -> S : register/accept { wrappedScopePrivateKey (persistent grant) }
note right of S : throwaway material deleted -> window closes
@enduml
```

`bootstrapSecret` only **gates the material fetch** (it is not a key). The server is blind at both ends —
it never holds the invite-wrapping private key (URL-fragment only). Variants (refined with the invites
use-case): **single-use** (default), **shared link** (riskier, explicit opt-in, capped registrations +
TTL + key rotation on close), and a **grant path** that carries no key in any channel and re-wraps on
explicit approval.

## Revocation & rotation

Revoking a member or share is **two-layered** (detail in the [Security Model](security-model.md)):
server-side token/membership invalidation is **instant** (no keys needed); **scope-keypair rotation**
(re-wrap to the remaining members) is the eventual defense-in-depth. Already-read content cannot be
retracted.

## Navigation contract — unified across kinds

One scope-agnostic navigation model serves personal, tenant and group alike — clients never need
separate "personal tree" / "tenant tree" / "group tree" APIs.

- **Server-visible:** scope id + kind; node id, parent id + kind; ordering and timestamps; blinded slug
  tokens; ciphertext/envelope references; grant/membership existence needed to decide visibility.
- **Client-decrypted:** space/folder names, Memory and chapter titles, cover metadata, captions, and the
  canonical path assembled from decrypted names.

Holding a session is **not** sufficient: the client must also hold/unlock the relevant scope keys before
it can decrypt any names or metadata. The operator must not learn private names or paths from navigation.

## Invariants

- Business data is addressed by **opaque scope/node IDs**; the **tenant context is resolved from the
  request `Host`** (tenant↔domain). No tenant or scope *name* appears in the path.
- **No cross-scope move** — moving across scopes is a copy + re-encrypt, not a tree move.
- No cycles; **no folder inside a Memory**.
- **Slug uniqueness per parent** across folders, Memories and chapters, via blinded slug tokens.
- A personal scope has **no other key-holder** unless the owner explicitly shares.
- The **same rules apply to all scope kinds**.

## Looking ahead — the Subscription layer (SaaS), parked

The SaaS idea (inbox #41) will introduce a **Subscription** as a billing envelope **between the
deployment (veyq.cloud) and scopes** — operating on operational metadata (counts/bytes), so it does not
touch operator-blindness. **"Tenant" stays the domain-scoped scope kind** (it is *not* the deployment or
the billing unit). Not designed here; refined separately when #41 is pulled.

## Related

- Concepts: [Security Model](security-model.md), [Account & Key Model](account-key-model.md).
- Features (refined later): Spaces, Memory, Sharing, Admin (tenant/group invites).
