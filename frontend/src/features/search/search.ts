// uc.search (web) — find memories/folders by name. Names are sealed (server-blind), so search is a CLIENT
// operation: load the active space's node graph once, decode the names, and substring-match locally.
import type { UnlockedSession } from '../account/login/login'
import type { ActiveSpace } from '../home/home'
import { decodeNode, type MemoryNode } from '../memory/createTree/memoryTree'
import { listNodesCached } from '../home/nodeGraph'

export type SearchHit = { id: string; name: string; type: MemoryNode['type']; node: MemoryNode }

/** Substring-match the active space's nodes by decrypted name (case-insensitive). Empty query → no hits. */
export async function searchNodes(apiBase: string, session: UnlockedSession, active: ActiveSpace, query: string): Promise<SearchHit[]> {
  const q = query.trim().toLowerCase()
  if (!q) return []
  const nodes = await listNodesCached(apiBase, session, active.scope.scopeId)
  return nodes
    .map((node) => ({ id: node.id, type: node.type, node, name: decodeNode(active.contentKey, node).name }))
    .filter((hit) => hit.name.toLowerCase().includes(q))
    .slice(0, 25)
}
