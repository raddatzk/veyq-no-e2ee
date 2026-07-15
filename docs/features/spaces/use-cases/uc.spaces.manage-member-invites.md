# UC: Manage pending member invites (list, re-issue link, revoke)

- ID: uc.spaces.manage-member-invites
- Primary actor: Tenant **Owner** (the admission gate — the only one who mints or manages member invites).
- Goal: give the owner control over the **admission** side after minting — see who has been **invited but not
  yet joined**, **re-issue** the invite link when the old one was lost or ignored, and **revoke** a pending
  invite so its outstanding secret can no longer be accepted.
- Preconditions: The owner holds the tenant's OWNER membership; the tenant is ACTIVE; at least one MEMBER
  invite has been minted ([uc.spaces.add-member](uc.spaces.add-member.md)).
- Postconditions (success): The owner sees the current pending member invites; a re-issue produces a fresh
  single-use secret + copyable link (the prior one superseded); a revoke marks the invite terminal so a
  replay of its secret is rejected. Already-**joined** members are never affected — this manages *invites*,
  not *memberships*. The server saw only invite metadata (email, status, expiry), never a plaintext secret.

This is the management companion to [uc.spaces.add-member](uc.spaces.add-member.md): add-member is deliberately
**additive** ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)), so tracking/re-issuing/
revoking the *invites* it produces lives here. It mirrors the owner-invite management the Server Owner already
has for tenant-owner invites ([uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md): resend
/ reassign / revoke).

## Main flow

1. The owner opens **"members"** and sees, beside the joined members
   ([uc.spaces.add-member](uc.spaces.add-member.md) directory), a list of **pending invites** — each an email +
   invite status + expiry. Owner-gated; a non-owner sees the directory but not the pending list nor the
   management actions.
2. **Re-issue.** For a pending invite whose link the invitee lost or ignored, the owner re-issues it: the server
   **supersedes** the current invite and mints a **fresh single-use secret** (the old one dies), and returns the
   new copyable link for the owner to hand off again. Same email, new secret, new expiry.
3. **Revoke.** The owner revokes a pending invite: it is soft-transitioned to a terminal state, so a later
   replay of that secret at `accept` is rejected as no-longer-valid. The reserved email is freed to re-invite.

## Alternate / exception flows

- **A1 — the invitee joined between list and action:** a re-issue/revoke targets an already-consumed invite → a
  no-op / not-found; the now-joined person shows in the member directory, not the pending list.
- **E1 — a non-owner attempts management:** rejected (403) — admission stays owner-only
  ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md): admission ≠ delivery).

## Quality notes

- **Operator-blind.** Listing returns only invite **metadata** (email, status, expiry) the server already
  stores; the secret is never stored plaintext (only its hash) and a re-issue returns the new secret only to
  the owner-gated caller over TLS.
- **Admission stays owner-only.** Every action here is gated on the tenant OWNER membership — the same gate that
  mints a member invite.
- **Invites, not memberships.** This never removes a joined member or rotates a scope key — those are a separate,
  later slice ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) /
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) defer removal + rotation). Revoking a
  *pending* invite is additive-safe: it touches no grant and no member.
- **Reuse.** The soft invite-lifecycle (ACTIVE → SUPERSEDED / EXPIRED, matched by hash, replay-rejected) already
  exists for owner invites; this applies it to MEMBER-role invites.

## What this means for you / Was das für dich bedeutet

You can see who you have invited but who has not joined yet, send someone their link again if they lost it, and
cancel an invite you sent by mistake — all without touching anyone who is already in your space. / Du siehst,
wen du eingeladen hast, der aber noch nicht beigetreten ist, kannst jemandem den Link erneut schicken, wenn er
verloren ging, und eine versehentliche Einladung zurückziehen — ohne jemanden anzutasten, der schon in deinem
Bereich ist.

## Related

- [uc.spaces.add-member](uc.spaces.add-member.md) — mints the member invites this manages.
- [uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md) — the owner-invite management this
  mirrors (resend / reassign / revoke).
- [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) — admission vs. delivery; additive-only.
