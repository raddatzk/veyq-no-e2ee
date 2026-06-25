import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import type { UnlockedSession } from '../../account/login/login'

export async function listFavoriteNodeIds(apiBase: string, session: UnlockedSession, scopeId: string): Promise<string[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${scopeId}/favorites`)
  if (!res.ok) throw await apiError(res, 'list memory favorites')
  return (await res.json()).nodeIds
}

export async function setMemoryFavorite(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, favorite: boolean): Promise<boolean> {
  const res = await authFetch(apiBase, session, `/api/scopes/${scopeId}/nodes/${nodeId}/favorite`, { method: favorite ? 'PUT' : 'DELETE' })
  if (!res.ok) throw await apiError(res, favorite ? 'favorite memory' : 'unfavorite memory')
  return (await res.json()).favorite
}
