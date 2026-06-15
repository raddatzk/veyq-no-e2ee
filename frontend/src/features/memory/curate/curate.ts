// uc.memory.curate (web) — create a folder/memory in the tree. The name is sealed and a per-parent slug
// token is derived (the server stays blind); a clean slug is generated from the name, with a numeric suffix
// on collision. Rename/move/reorder need new backend endpoints (only createNode exists today).
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { createNode, type MemoryNode, type NodeType } from '../createTree/memoryTree'

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
