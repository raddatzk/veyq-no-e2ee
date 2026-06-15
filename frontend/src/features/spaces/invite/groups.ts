// uc.spaces.groups (web) — owner-side group management built on the existing tenant + invite slices:
// create a shared group (a tenant the caller owns) and mint invite links to add people. The heavy crypto
// (scope keypair, temp-wrap) lives in the tenant / inviteMembers slices; this only orchestrates them.
import type { UnlockedSession } from '../../account/login/login'
import { claimTenantOwnership, createTenant, issueOwnerInvite, type TenantSummary } from '../../admin/createTenantInviteOwner/tenant'
import { issueMemberInvite, type MemberRole } from '../../admin/inviteMembersRoles/inviteMembers'
import { buildInviteLink } from './inviteLink'

/** A URL-safe tenant handle from a display name (the server keys tenants by it). */
function slugify(name: string): string {
  return name.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'group'
}

/** Create a shared group the caller owns: a tenant + a self owner-claim (mints + self-grants the scope). */
export async function createGroup(apiBase: string, session: UnlockedSession, displayName: string): Promise<TenantSummary> {
  const base = slugify(displayName)
  for (let attempt = 0; attempt < 25; attempt++) {
    const handle = attempt === 0 ? base : `${base}-${attempt + 1}`
    try {
      const tenant = await createTenant(apiBase, session.sessionToken, handle, displayName.trim())
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

/** Mint a member invite for a group and return a shareable link (secrets ride the URL fragment). */
export async function inviteToGroup(apiBase: string, session: UnlockedSession, tenantId: string, role: MemberRole, origin: string): Promise<string> {
  const invite = await issueMemberInvite(apiBase, session, tenantId, role)
  return buildInviteLink(origin, { inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret, tempPrivateKey: invite.tempPrivateKey })
}

/** Delete a tenant (workspace) and everything it owns — its scope, all groups, members, invites. Owner only. */
export async function deleteTenant(apiBase: string, session: UnlockedSession, tenantId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}`, { method: 'DELETE', headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`delete tenant failed: ${res.status} ${await res.text()}`)
}
