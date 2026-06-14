// uc.memory.browse (web) — navigate the encrypted tree. The server is blind to names and paths, so path
// resolution is a CLIENT operation: walk one level per path segment (GET /nodes?parentId=…), matching each
// slug to a child by its blinded slug token (no decryption needed to navigate). The node's TYPE comes from
// the node itself (server-visible structure), NOT from the URL — so /urlaube/italien-2026 stays clean and
// the client learns at each step whether it's a Folder, Memory or Chapter.
import type { UnlockedSession } from '../../account/login/login'
import { slugTokenFor, type MemoryNode } from '../createTree/memoryTree'

export type { MemoryNode } from '../createTree/memoryTree'
export { decodeNode } from '../createTree/memoryTree'

/** List the direct children of a level: a node id, or the scope root when parentId is omitted. */
export async function listChildren(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string,
  parentId?: string,
): Promise<MemoryNode[]> {
  const slot = parentId ?? 'root'
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes?parentId=${encodeURIComponent(slot)}`, {
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw new Error(`list children failed: ${res.status} ${await res.text()}`)
  return (await res.json()).nodes
}

/**
 * Resolve a clean slug path (e.g. ['urlaube','italien-2026']) to the chain of nodes, one per segment.
 * Each step lists the current level and matches the slug to a child by its per-parent slug token. Throws
 * if a segment doesn't exist. The caller reads each node's `type` to know the context (folder/memory/chapter).
 */
export async function resolvePath(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string,
  contentKey: Uint8Array,
  segments: string[],
): Promise<MemoryNode[]> {
  const chain: MemoryNode[] = []
  let parentId: string | undefined // undefined → root
  let parentSlot = scopeId // the root's parent slot is the scope id (the server's sentinel)
  for (const slug of segments) {
    const children = await listChildren(apiBase, session, scopeId, parentId)
    const token = slugTokenFor(contentKey, parentSlot, slug)
    const node = children.find((c) => c.slugToken === token)
    if (!node) throw new Error(`path segment not found: ${slug}`)
    chain.push(node)
    parentId = node.id
    parentSlot = node.id
  }
  return chain
}
