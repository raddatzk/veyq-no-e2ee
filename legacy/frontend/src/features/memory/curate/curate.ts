// uc.memory.curate (web) — create a folder/memory in the tree. The name is sealed and a per-parent slug
// token is derived (the server stays blind). Folder, Memory and Chapter children share one per-parent slug
// namespace, so collisions are surfaced to the user instead of silently creating a numbered permalink.
import { apiError } from '../../../shared/apiError'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { createNode, decodeNode, resealNode, slugTokenFor, type MemoryNode, type NodeType } from '../createTree/memoryTree'
import { invalidateNodeGraph } from '../../home/nodeGraph'
import { b64url } from '../../../shared/base64'

const parentSlot = (active: ActiveSpace, parentId: string | null | undefined) => parentId ?? active.scope.scopeId
const NODE_NAME_CONFLICT = 'NODE_NAME_CONFLICT'

function isSlugConflict(e: unknown): boolean {
  const msg = e instanceof Error ? e.message : String(e)
  return msg.includes('SLUG_EXISTS') || /\b409\b/.test(msg) || msg.includes(NODE_NAME_CONFLICT)
}

export function isNodeNameConflict(e: unknown): boolean {
  return isSlugConflict(e)
}

async function patchNode(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, body: Record<string, unknown>): Promise<MemoryNode> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw await apiError(res, 'update node')
  invalidateNodeGraph(scopeId) // rename/move/cover/reorder changed the graph — drop the per-scope cache
  return res.json()
}

/** A URL-safe slug from a display name (diacritics stripped, non-alphanumerics → dashes). */
export function slugify(name: string): string {
  return (
    name
      .normalize('NFKD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '') || 'item'
  )
}

/** Create a child node under a parent (or the root when parentId is omitted). */
export async function createChild(
  apiBase: string,
  session: UnlockedSession,
  active: ActiveSpace,
  type: NodeType,
  name: string,
  orderIndex: number,
  parentId?: string,
): Promise<MemoryNode> {
  const trimmedName = name.trim()
  try {
    const created = await createNode(apiBase, session, active.scope.scopeId, active.contentKey, { type, name: trimmedName, slug: slugify(trimmedName), orderIndex, parentId })
    invalidateNodeGraph(active.scope.scopeId) // a new node — drop the per-scope cache so reads see it
    return created
  } catch (e) {
    if (isSlugConflict(e)) throw new Error(NODE_NAME_CONFLICT)
    throw e
  }
}

/** Rename a node: re-seal {name, slug} and recompute the per-parent slug token. */
export async function renameNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode, newName: string): Promise<MemoryNode> {
  const slot = parentSlot(active, node.parentId)
  const name = newName.trim()
  const slug = slugify(name)
  const sealed = b64url(resealNode(active.contentKey, { name, slug }))
  try {
    return await patchNode(apiBase, session, active.scope.scopeId, node.id, {
      sealedContent: { suiteId: 'v1', bytes: sealed },
      slugToken: slugTokenFor(active.contentKey, slot, slug),
    })
  } catch (e) {
    if (isSlugConflict(e)) throw new Error(NODE_NAME_CONFLICT)
    throw e
  }
}

/** Move a node under a new parent ("root" or a node id), recomputing the slug token. */
export async function moveNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode, newParentId: string): Promise<MemoryNode> {
  const content = decodeNode(active.contentKey, node)
  const newSlot = newParentId === 'root' ? active.scope.scopeId : newParentId
  const slug = slugify(content.name)
  const body: Record<string, unknown> = { newParentId, slugToken: slugTokenFor(active.contentKey, newSlot, slug) }
  if (content.slug !== slug) body.sealedContent = { suiteId: 'v1', bytes: b64url(resealNode(active.contentKey, { name: content.name, slug })) }
  try {
    return await patchNode(apiBase, session, active.scope.scopeId, node.id, body)
  } catch (e) {
    if (isSlugConflict(e)) throw new Error(NODE_NAME_CONFLICT)
    throw e
  }
}

/** Pin a media of this memory as its cover (empty string clears it). */
export function setCover(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, coverMediaId: string): Promise<MemoryNode> {
  return patchNode(apiBase, session, active.scope.scopeId, nodeId, { coverMediaId })
}

/** Set a node's sibling order index. */
export function reorderNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, orderIndex: number): Promise<MemoryNode> {
  return patchNode(apiBase, session, active.scope.scopeId, nodeId, { orderIndex })
}

/** Delete a node (folder or memory) and everything beneath it — recursive cascade on the server. */
export async function deleteNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'delete node')
  invalidateNodeGraph(active.scope.scopeId) // the subtree is gone — drop the per-scope cache
}
