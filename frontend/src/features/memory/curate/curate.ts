// uc.memory.curate (web) — create a folder/memory in the tree. The name is sealed and a per-parent slug
// token is derived (the server stays blind); a clean slug is generated from the name, with a numeric suffix
// on collision. Rename/move/reorder need new backend endpoints (only createNode exists today).
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { createNode, decodeNode, resealNode, slugTokenFor, type MemoryNode, type NodeType } from '../createTree/memoryTree'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const parentSlot = (active: ActiveSpace, parentId: string | null | undefined) => parentId ?? active.scope.scopeId

async function patchNode(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, body: Record<string, unknown>): Promise<MemoryNode> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`update node failed: ${res.status} ${await res.text()}`)
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

/** Create a child node under a parent (or the root when parentId is omitted), retrying the slug on collision. */
export async function createChild(
  apiBase: string,
  session: UnlockedSession,
  active: ActiveSpace,
  type: NodeType,
  name: string,
  orderIndex: number,
  parentId?: string,
): Promise<MemoryNode> {
  const base = slugify(name)
  for (let attempt = 0; attempt < 25; attempt++) {
    const slug = attempt === 0 ? base : `${base}-${attempt + 1}`
    try {
      return await createNode(apiBase, session, active.scope.scopeId, active.contentKey, { type, name: name.trim(), slug, orderIndex, parentId })
    } catch (e) {
      const msg = String(e)
      if (!msg.includes('SLUG_EXISTS') && !msg.includes('409')) throw e
    }
  }
  throw new Error('could not create node (slug conflict)')
}

/** Rename a node: re-seal {name, slug} and recompute the per-parent slug token, retrying on collision. */
export async function renameNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode, newName: string): Promise<MemoryNode> {
  const slot = parentSlot(active, node.parentId)
  const base = slugify(newName)
  const name = newName.trim()
  for (let attempt = 0; attempt < 25; attempt++) {
    const slug = attempt === 0 ? base : `${base}-${attempt + 1}`
    const sealed = b64url(resealNode(active.contentKey, { name, slug }))
    try {
      return await patchNode(apiBase, session, active.scope.scopeId, node.id, {
        sealedContent: { suiteId: 'v1', bytes: sealed },
        slugToken: slugTokenFor(active.contentKey, slot, slug),
      })
    } catch (e) {
      if (!String(e).includes('SLUG_EXISTS') && !String(e).includes('409')) throw e
    }
  }
  throw new Error('could not rename (slug conflict)')
}

/** Move a node under a new parent ("root" or a node id), recomputing the slug token (and re-slugging on collision). */
export async function moveNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode, newParentId: string): Promise<MemoryNode> {
  const { name } = decodeNode(active.contentKey, node)
  const newSlot = newParentId === 'root' ? active.scope.scopeId : newParentId
  const base = slugify(name)
  for (let attempt = 0; attempt < 25; attempt++) {
    const slug = attempt === 0 ? base : `${base}-${attempt + 1}`
    const body: Record<string, unknown> = { newParentId, slugToken: slugTokenFor(active.contentKey, newSlot, slug) }
    if (attempt > 0) body.sealedContent = { suiteId: 'v1', bytes: b64url(resealNode(active.contentKey, { name, slug })) }
    try {
      return await patchNode(apiBase, session, active.scope.scopeId, node.id, body)
    } catch (e) {
      if (!String(e).includes('SLUG_EXISTS') && !String(e).includes('409')) throw e
    }
  }
  throw new Error('could not move (slug conflict)')
}

/** Pin a media of this memory as its cover (empty string clears it). */
export function setCover(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, coverMediaId: string): Promise<MemoryNode> {
  return patchNode(apiBase, session, active.scope.scopeId, nodeId, { coverMediaId })
}

/** Set a node's sibling order index. */
export function reorderNode(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, orderIndex: number): Promise<MemoryNode> {
  return patchNode(apiBase, session, active.scope.scopeId, nodeId, { orderIndex })
}
