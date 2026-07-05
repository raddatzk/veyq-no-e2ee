# ADR 0014 — Group scopes & governance: shared scopes, private even from the tenant owner

- Status: Accepted
- Date: 2026-06-28

## Context

The [spaces feature](../features/spaces/_index.md) always planned **groups** ("create / join a group"). The
foundation is in place: scope identity (opaque `scopeId` + `kind` TENANT/GROUP/PERSONAL,
[ADR 0002](0002-personal-scope-per-tenant.md) + [en.spaces.personal-scope](../features/spaces/enablers/en.spaces.personal-scope.md)),
membership = role + grant ([ADR 0007](0007-membership-role-grant-relation.md)), the hybrid-KEM grant
([ADR 0009](0009-native-crypto-per-platform-vectors.md)), and the content-key hierarchy
([ADR 0012](0012-content-key-hierarchy.md), where a scope's `K_SC` is already "one key, many wraps"). A group
is therefore **mostly a multi-member scope** — little new crypto. What is *not* yet settled, and what this ADR
nails, is the **governance**: who may do what, how members are added operator-blind, the group lifecycle, and
— the question that drove the refinement — **what relationship, if any, a tenant owner has to groups**.

Considered and rejected during refinement:

- **Tenant owner can see all groups in the tenant (read-only oversight).** Rejected. Visibility without any
  capability is **surveillance, not governance** — and group existence + membership are *already* server-side
  metadata the invariant sanctions (the server must hold scope + membership rows to route grants), which on a
  **self-hosted** instance the tenant owner — frequently also the operator — can read at the DB level anyway.
  A product feature would add UI + a "the admin watches your groups" signal for **zero new power**, while the
  real guarantee (names + content sealed) holds regardless. Dropped entirely.
- **Tenant owner can force-delete a group, or add themselves to one.** Rejected — both break the core promise
  that a group is confidential *even from a privileged tenant role*. The only legitimate need behind them —
  **orphaned groups** when an owner leaves the tenant — is met by an automatic rule, not an admin power (below).
- **Plaintext group name stored server-side.** Rejected — a leak; the name is sealed under `K_SC` like any
  node name ([ADR 0012](0012-content-key-hierarchy.md)).
- **Email-invite outsiders as the first way to add a member.** Deferred (not rejected) — start with
  **username search of existing tenant members** (their identity public key already exists); email-invite
  onboarding is a later, heavier flow.
- **Server-computed group navigation / a server-side group slug.** Rejected — like the personal scope, groups
  are addressed and navigated **client-side**: the `@group` URL fragment resolves against the member's own
  decrypted group list ([ADR 0015](0015-operator-blind-readable-urls.md)).

## Decision

**A group is a `kind=GROUP` scope, born warm by an existing tenant member, whose members each hold a grant —
and which is confidential even from the tenant owner.**

1. **Birth (warm, client-driven).** An existing, unlocked tenant member (the **GroupOwner**) mints it like a
   personal scope ([en.spaces.personal-scope](../features/spaces/enablers/en.spaces.personal-scope.md)) but
   **named + multi-member-capable**: generate a random `K_SC`, **seal the group name under `K_SC`**, and
   `asymWrap` `K_SC` to their **own** identity public key (the self-grant). `POST` the ciphertext name + grant;
   the server mints the `GROUP` scope + the creator's `role=OWNER` membership. No new crypto — the KEM grant of
   [ADR 0009](0009-native-crypto-per-platform-vectors.md), the content keys of [ADR 0012](0012-content-key-hierarchy.md).

2. **Key distribution = one grant per member.** A member holds the group iff a membership row carries `K_SC`
   `asymWrap`'d to *their* identity public key. Adding a member is exactly: produce that grant and `POST` it.
   The server never holds `K_SC`.

3. **The name is sealed under `K_SC`.** Only members decrypt it. The server — and anyone with server/DB access,
   including a self-hosting tenant owner — sees an **unnamed** group: opaque `scopeId`, `kind`, members. This is
   what makes a group **private even from the tenant owner**.

4. **Authority model.**

   | Role | May |
   |---|---|
   | **GroupOwner** (`OWNER` on a GROUP scope) | create the group · **add members via username search** · **promote a member to co-owner / demote a co-owner** · **delete the group with a grace period** |
   | **Member** (any role with a grant) | read + write the group's content (holds `K_SC`) |
   | **TenantOwner** | **nothing** with respect to groups — groups are **not surfaced** to them, and they cannot delete a group or add themselves |

   Only two roles — **OWNER / MEMBER** (no `ADMIN`; an admin-over-groups would be surveillance for zero power,
   above). A group follows the cross-cutting ownership rule ([ADR 0007](0007-membership-role-grant-relation.md)):
   **multiple owners allowed, at least one required** — the last owner cannot be removed or demoted (the
   last-owner guard); a sole owner leaving the tenant triggers the orphan rule (§6).

5. **Adding a member = username search + consent.** The GroupOwner searches by **username** — a **per-tenant,
   unique-within-tenant** handle ([ADR 0023](0023-per-tenant-member-identity.md)), not email; the server resolves
   it to the target's **identity public key**.
   - **Default = direct add.** The owner picks the member → the server records a grant-less GROUP membership →
     grant-delivery ([ADR 0021](0021-operator-blind-background-tasks.md)) wraps `K_SC` to them, first-online-wins
     ([ADR 0020](0020-grant-distribution-invite-wrap.md)). They simply find themselves in the group.
   - **Optional consent (a per-user "ask before adding me" toggle) is consent-first, never wrap-then-soft.** A
     non-auto-join member is *proposed*, held as a **soft, grant-less** membership; grant-delivery **skips** soft
     memberships, so **`K_SC` never reaches someone who hasn't accepted** (deleting an already-delivered wrap
     would not un-give the key — so we gate *before* the wrap, not clean up after). To let them decide informed,
     the owner's device **one-off seals just the group name** to the invitee's identity public key
     (`asymWrap(inviteePub, name)`) — no standing second key, no second rotation burden. The invitee sees the
     name + who added them; **Accept** flips the membership eligible → a key-holder wraps `K_SC` (the usual
     "awaiting access" window); **Decline** drops the soft membership (nothing was ever wrapped).
   - **Threat:** a malicious operator could return a public key it controls (MITM) and read the group.
     **Defense:** the **safety-number / fingerprint** of that identity
     ([cr-0025](../../changes/cr-0025-native-hybrid-kem.md); UI deferred). The exact verification stance
     (TOFU-pin vs. a hard gate before the first grant) is **deferred to the member-add slice (G2)** — proposed:
     TOFU-pin + surface the fingerprint, hard-gate later.

6. **Lifecycle.**
   - **Owner delete = soft + grace.** The GroupOwner deletes; the group enters a **pending-deletion** state and
     is purged by a scheduled job after a **grace period** (restorable by the owner in-window). Mirrors the
     existing scheduled-purge pattern (`TenantInvitePurge` / `SessionPurge`, [cr-0011](../../changes/cr-0011-session-token-purge.md)).
     The grace length + restore UX are settled in the lifecycle slice (G3).
   - **Orphans = an automatic rule, not an admin power.** When a user is removed from a tenant, the groups they
     **solely** own are auto-tombstoned (grace) or their ownership transferred — a server rule, so no tenant-owner
     snooping power is needed (G3).
   - **Removal → rotation.** A removed member keeps the `K_SC` they already held; confidentiality of **new**
     content is restored by **rotating `K_SC`** (re-wrap to the remaining members, re-seal new content) per
     [ADR 0012](0012-content-key-hierarchy.md). "Stop new grants + rotate forward"; already-read content cannot
     be retracted (a stated limitation). Detail in G3.

7. **Group favorites are per-user under the identity key, not `K_SC`.** The favorites blob
   ([en.memory.favorites](../features/memory/enablers/en.memory.favorites.md)) seals under `K_SC` for a personal
   scope, but `K_SC` is **shared** in a group, so a group's per-user favorites must seal under the **member's own
   identity key** to stay private to them. Folded in when favorites reach group scopes.

## Consequences

- **Groups reuse the content stack.** The Memory tree ([ADR 0012](0012-content-key-hierarchy.md)), browse, and
  (with the identity-key caveat) favorites work per-scope already — a group is "another scope" to them.
- **Confidential even from the tenant owner.** The strongest practical privacy statement veyq makes: a group's
  *content and name* are sealed; only its *existence + membership* are visible to whoever runs the server, and
  the product surfaces neither to the tenant owner.
- **No new crypto, no new operator trust.** Member grants are the same hybrid KEM; the only new server state is
  the GROUP scope row's sealed name + the per-member grants + the pending-deletion lifecycle.
- **Sliced delivery.** **G1** — create + open + browse a group (solo owner; this ADR's substrate,
  [en.spaces.group-scope](../features/spaces/enablers/en.spaces.group-scope.md)). **G2** — add a member
  (username search + KEM grant + the fingerprint stance). **G3** — member management, grace-period delete +
  orphan rule, key rotation.
- **Deferred, on purpose:** the fingerprint verification stance (G2), grace length + restore + rotation
  mechanics (G3), and email-invite onboarding (later). They are recorded here so the slices inherit the frame.
