// uc.search (web) — find memories/folders by name. Names are sealed (server-blind), so search is a CLIENT
// operation: load the active space's node graph once, decode the names, and substring-match locally.
import type { UnlockedSession } from '../account/login/login'
import type { ActiveSpace } from '../home/home'
import { decodeNode, type MemoryNode } from '../memory/createTree/memoryTree'
import { listNodesCached } from '../home/nodeGraph'
import { listParticipants } from '../people/participants/participants'
import { mapPool } from '../../shared/pool'

export type SearchHit = { id: string; name: string; type: MemoryNode['type']; node: MemoryNode; participantNames?: string[] }

/** Pure name filter: case-insensitive substring match, empty/whitespace query → none, capped at 25. */
export function matchByName<T extends { name: string }>(items: T[], query: string): T[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  return items.filter((it) => it.name.toLowerCase().includes(q)).slice(0, 25)
}

/** Match node names plus decrypted participant labels; still returns node hits, capped at 25. */
export function matchByNameOrParticipants<T extends { name: string; participantNames?: string[] }>(items: T[], query: string): T[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  return items.filter((it) => [it.name, ...(it.participantNames ?? [])].join(' ').toLowerCase().includes(q)).slice(0, 25)
}

/** Substring-match the active space's nodes by decrypted name (case-insensitive). Empty query → no hits. */
export async function searchNodes(apiBase: string, session: UnlockedSession, active: ActiveSpace, query: string): Promise<SearchHit[]> {
  if (!query.trim()) return []
  const nodes = await listNodesCached(apiBase, session, active.scope.scopeId)
  const hits = nodes.map((node) => ({ id: node.id, type: node.type, node, name: decodeNode(active.contentKey, node).name }))
  const enriched = await mapPool(hits, 6, async (hit) => {
    if (hit.type === 'FOLDER') return hit
    try {
      const participants = await listParticipants(apiBase, session, active, hit.id)
      return { ...hit, participantNames: participants.map((p) => p.displayName) }
    } catch {
      return hit
    }
  })
  return matchByNameOrParticipants(enriched, query)
}
