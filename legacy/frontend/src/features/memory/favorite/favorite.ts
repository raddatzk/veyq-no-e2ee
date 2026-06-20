import { apiError } from '../../../shared/apiError'
import type { UnlockedSession } from '../../account/login/login'

const auth = (session: UnlockedSession) => ({ authorization: `Bearer ${session.sessionToken}` })

export async function listFavoriteNodeIds(apiBase: string, session: UnlockedSession, scopeId: string): Promise<string[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/favorites`, { headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'list memory favorites')
  return (await res.json()).nodeIds
}

export async function setMemoryFavorite(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, favorite: boolean): Promise<boolean> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/favorite`, {
    method: favorite ? 'PUT' : 'DELETE',
    headers: auth(session),
  })
  if (!res.ok) throw await apiError(res, favorite ? 'favorite memory' : 'unfavorite memory')
  return (await res.json()).favorite
}
