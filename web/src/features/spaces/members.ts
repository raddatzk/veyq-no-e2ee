// uc.spaces.add-member (web, cr-0077) — the member-management data layer for a tenant OWNER. Admission
// (inviting by email) is tenant-owner-gated; the member directory (pubkeys + grant status) is any key-holder's.
// Operator-blind: the server holds only invite metadata + already-stored operational metadata (pubkeys/roles/
// grant-present) — never a scope key. The actual key DELIVERY runs as a grant-delivery job (cr-0076), not here.
import { tenantsApi, scopesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import type { MemberInviteState, PendingMemberInvite } from '../../api/generated/tenants'
import type { ScopeMember } from '../../api/generated/scopes'

export type { ScopeMember } from '../../api/generated/scopes'
export type { MemberInviteState, PendingMemberInvite } from '../../api/generated/tenants'

/** Invite a person (by email) into the active tenant as a MEMBER — owner-gated (403 `not_tenant_owner`). */
export function inviteMember(tenantId: string, email: string): Promise<MemberInviteState> {
  return withRefresh(() => tenantsApi().inviteTenantMember({ tenantId, memberInviteRequest: { email } }))
}

/** List the tenant's pending member invites (invited-but-not-joined) — owner-gated (uc.spaces.manage-member-invites). */
export function listMemberInvites(tenantId: string): Promise<PendingMemberInvite[]> {
  return withRefresh(() => tenantsApi().listMemberInvites({ tenantId }))
}

/** Re-issue a pending member invite: supersede the old secret + mint a fresh copyable link — owner-gated. */
export function reissueMemberInvite(tenantId: string, inviteId: string): Promise<MemberInviteState> {
  return withRefresh(() => tenantsApi().reissueMemberInvite({ tenantId, inviteId }))
}

/** Revoke a pending member invite (soft-terminal; its secret no longer accepts) — owner-gated. */
export function revokeMemberInvite(tenantId: string, inviteId: string): Promise<void> {
  return withRefresh(() => tenantsApi().revokeMemberInvite({ tenantId, inviteId }))
}

/** List a scope's members with their identity pubkeys + grant status (the directory) — key-holder only. */
export function listScopeMembers(scopeId: string): Promise<ScopeMember[]> {
  return withRefresh(() => scopesApi().listScopeMembers({ scopeId }))
}

/**
 * Add an existing tenant member to a **group** by username (uc.spaces.add-group-member, cr-0086) — owner-gated
 * (`403 not_scope_owner`), tenant-scoped resolve (`404 member_not_found`), idempotency (`409 already_member`).
 * Records a grant-less membership; the key is delivered later by grant-delivery (cr-0076), not here. Returns the
 * new member's directory row (`hasGrant=false`, awaiting access).
 */
export function addGroupMember(scopeId: string, username: string): Promise<ScopeMember> {
  return withRefresh(() => scopesApi().addGroupMember({ scopeId, addGroupMemberRequest: { username } }))
}

/**
 * Decode a failed member call into `{ status, code }` for UI mapping (401 → re-login, 403 → not owner /
 * not a member, 409 → already_member / tenant_not_active). Duck-typed so it handles the ResponseError of
 * either generated package. Returns status 0 for a non-HTTP failure (e.g. network).
 */
export async function memberError(e: unknown): Promise<{ status: number; code: string | null }> {
  const res = (e as { response?: Response })?.response
  if (res && typeof res.status === 'number') {
    let code: string | null = null
    try {
      code = ((await res.clone().json()) as { code?: string })?.code ?? null
    } catch {
      // no / non-JSON body — leave code null
    }
    return { status: res.status, code }
  }
  return { status: 0, code: null }
}
