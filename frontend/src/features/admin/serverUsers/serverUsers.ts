// uc.admin.server-users (web) — the instance roster + server-role assignment, Server Owner only. Operator-
// blind: the server returns only id/email/displayName/serverRole (no keys/content), so the owner can promote
// a member to Server Owner (the role that gates tenant creation) or demote back. Backend keeps ≥1 owner.
import { apiError } from '../../../shared/apiError'
import type { UnlockedSession } from '../../account/login/login'

export type ServerRole = 'ServerOwner' | 'ServerMember'
export type ServerUser = { id: string; email: string; displayName: string; serverRole: ServerRole }

export async function listServerUsers(apiBase: string, session: UnlockedSession): Promise<ServerUser[]> {
  const res = await fetch(`${apiBase}/api/admin/users`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'list server users')
  return (await res.json()).users
}

export async function setServerRole(apiBase: string, session: UnlockedSession, userId: string, serverRole: ServerRole): Promise<ServerUser> {
  const res = await fetch(`${apiBase}/api/admin/users/${encodeURIComponent(userId)}/server-role`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ serverRole }),
  })
  if (!res.ok) throw await apiError(res, 'set server role')
  return res.json()
}
