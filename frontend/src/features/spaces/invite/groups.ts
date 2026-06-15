// uc.spaces.groups (web) — owner-side group management built on the existing tenant + invite slices:
// create a shared group (a tenant the caller owns) and mint invite links to add people. The heavy crypto
// (scope keypair, temp-wrap) lives in the tenant / inviteMembers slices; this only orchestrates them.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import { claimTenantOwnership, createTenant, issueOwnerInvite, type TenantSummary } from '../../admin/createTenantInviteOwner/tenant'
import { issueMemberInvite, type MemberRole } from '../../admin/inviteMembersRoles/inviteMembers'
import { buildInviteLink, buildProtectedInviteLink, type ProtectedInviteParts } from './inviteLink'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

// The passphrase that protects an invite's temp key is folded into a KEK via Argon2id (same primitive as
// password-protected shares). The temp key is sym-wrapped under it; the link carries only the ciphertext +
// salt, so whoever relays the email (the server, the mail provider) can't resolve it without the passphrase.
const TEMP_KEY_CONTEXT = 'invite-temp-key'

/** Encrypt a temp private key under a passphrase → the `ek`/`ps` link fields (operator-blind invite). */
export function protectTempKey(tempPrivateKeyB64: string, passphrase: string): { encTempKey: string; pwSalt: string } {
  const salt = v1.randomBytes(16)
  const kek = v1.kekRecovery(v1.utf8(passphrase), salt)
  const enc = v1.symWrap(kek, fromB64url(tempPrivateKeyB64), TEMP_KEY_CONTEXT, v1.randomBytes(12))
  return { encTempKey: b64url(enc), pwSalt: b64url(salt) }
}

/** Decrypt a passphrase-protected temp key back to its base64 form (wrong passphrase → AEAD failure). */
export function unprotectTempKey(p: { encTempKey: string; pwSalt: string }, passphrase: string): string {
  const kek = v1.kekRecovery(v1.utf8(passphrase), fromB64url(p.pwSalt))
  return b64url(v1.symUnwrap(kek, fromB64url(p.encTempKey), TEMP_KEY_CONTEXT))
}

/** A URL-safe tenant handle from a display name (the server keys tenants by it). */
function slugify(name: string): string {
  return name.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'group'
}

/**
 * Create a shared group the caller owns: a tenant + a self owner-claim (mints + self-grants the scope).
 * A tenant is domain-scoped (the host resolves it), so a domain is required; tests may omit it (derived
 * from the unique handle) but the UI always supplies one.
 */
export async function createGroup(apiBase: string, session: UnlockedSession, displayName: string, domain?: string): Promise<TenantSummary> {
  const base = slugify(displayName)
  for (let attempt = 0; attempt < 25; attempt++) {
    const handle = attempt === 0 ? base : `${base}-${attempt + 1}`
    try {
      const tenant = await createTenant(apiBase, session.sessionToken, handle, displayName.trim(), domain ?? `${handle}.test`)
      const invite = await issueOwnerInvite(apiBase, session.sessionToken, tenant.id)
      await claimTenantOwnership(apiBase, session.sessionToken, invite.inviteId, invite.bootstrapSecret, session.identityPublicKey.kemPublicKey)
      return tenant
    } catch (e) {
      const msg = String(e)
      if (!msg.includes('409') && !msg.toLowerCase().includes('exists')) throw e
    }
  }
  throw new Error('could not create group (handle conflict)')
}

/**
 * Mint a member invite for a group and return a shareable link (secrets ride the URL fragment). With a
 * passphrase, the link is the operator-blind variant: the temp key is encrypted under it (`ek`/`ps`), so
 * the link alone can't join — the recipient also needs the passphrase (share it out of band).
 */
export async function inviteToGroup(apiBase: string, session: UnlockedSession, tenantId: string, role: MemberRole, origin: string, passphrase?: string): Promise<string> {
  const invite = await issueMemberInvite(apiBase, session, tenantId, role)
  if (passphrase && passphrase.length > 0) {
    const protectedParts: ProtectedInviteParts = {
      inviteId: invite.inviteId,
      bootstrapSecret: invite.bootstrapSecret,
      ...protectTempKey(invite.tempPrivateKey, passphrase),
    }
    return buildProtectedInviteLink(origin, protectedParts)
  }
  return buildInviteLink(origin, { inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret, tempPrivateKey: invite.tempPrivateKey })
}

/**
 * Mint a member invite and have the server email the link to an address. The link is assembled client-side
 * (here), so the mode determines what the server/mail transit sees: pragmatic = the temp key in the clear;
 * passphrase = only the encrypted temp key (the passphrase is shared out of band). The server validates
 * ownership + that the link targets this instance, then relays it.
 */
export async function emailInviteToGroup(
  apiBase: string,
  session: UnlockedSession,
  tenantId: string,
  role: MemberRole,
  email: string,
  origin: string,
  passphrase?: string,
): Promise<void> {
  const invite = await issueMemberInvite(apiBase, session, tenantId, role)
  const link = passphrase && passphrase.length > 0
    ? buildProtectedInviteLink(origin, { inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret, ...protectTempKey(invite.tempPrivateKey, passphrase) })
    : buildInviteLink(origin, { inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret, tempPrivateKey: invite.tempPrivateKey })
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/member-invites/email`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ email: email.trim(), inviteId: invite.inviteId, link }),
  })
  if (!res.ok) throw new Error(`email invite failed: ${res.status} ${await res.text()}`)
}

/**
 * Schedule a tenant (workspace) for deletion after a grace period. Owner only. The tenant stays usable
 * until the deadline (members secure their data), then the server GC purges it — scope, groups, members,
 * invites. Idempotent; the deadline is exposed on the roster as scheduledDeletionAt.
 */
export async function deleteTenant(apiBase: string, session: UnlockedSession, tenantId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}`, { method: 'DELETE', headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`delete tenant failed: ${res.status} ${await res.text()}`)
}

/** Cancel a scheduled tenant deletion (clears the deadline). Owner only. */
export async function cancelTenantDeletion(apiBase: string, session: UnlockedSession, tenantId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/cancel-deletion`, { method: 'POST', headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`cancel tenant deletion failed: ${res.status} ${await res.text()}`)
}
