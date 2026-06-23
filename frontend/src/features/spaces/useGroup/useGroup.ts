// uc.spaces.use-group (web) — enter a group space and collaborate on its shared content. A group is a
// tenant the caller is an active member of AND holds the scope key for; the heavy lifting (open scope,
// content key, read/write items) is the scope-generic content slice — this only adds the group roster.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import type { UnlockedSession } from '../../account/login/login'

export type GroupSpace = {
  tenantId: string
  name: string
  displayName: string
  logoUrl?: string
  role: string
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
}

/** List the group spaces the caller can enter (active member + holds the scope key). */
export async function listMyGroups(apiBase: string, session: UnlockedSession): Promise<GroupSpace[]> {
  const res = await authFetch(apiBase, session, '/api/me/groups')
  if (!res.ok) throw await apiError(res, 'list groups')
  return (await res.json()).groups
}
