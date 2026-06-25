// Per-scope node-graph cache. Search, move-destinations and the ancestor chain each need the active space's
// FULL node list, and a single user interaction can ask for several in a row (e.g. open the move picker, then
// type into search). Memoize the list per scope for a short window so a burst hits the server once; node
// mutations (create/rename/move/delete) drop the stale entry via invalidateNodeGraph, and the short TTL
// self-heals any path that forgets to. Decryption stays at the call sites (this only caches ciphertext).
import type { UnlockedSession } from '../account/login/login'
import { listNodes, type MemoryNode } from '../memory/createTree/memoryTree'

const TTL_MS = 10_000
const cache = new Map<string, { at: number; nodes: Promise<MemoryNode[]> }>()

/** The active space's node list, served from the per-scope cache when fresh (else fetched + memoized). */
export function listNodesCached(apiBase: string, session: UnlockedSession, scopeId: string): Promise<MemoryNode[]> {
  const hit = cache.get(scopeId)
  const now = Date.now()
  if (hit && now - hit.at < TTL_MS) return hit.nodes
  // A failed fetch must not stick — drop the entry so the next call retries against the server.
  const nodes = listNodes(apiBase, session, scopeId).catch((e) => { cache.delete(scopeId); throw e })
  cache.set(scopeId, { at: now, nodes })
  return nodes
}

/** Drop a scope's cached node list after a mutation so the next read reflects it. */
export function invalidateNodeGraph(scopeId: string): void { cache.delete(scopeId) }
