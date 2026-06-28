// Client-side tree navigation (cr-0030, uc.memory.browse). The operator-blind server computes no navigation,
// so the current level + the breadcrumb are derived here from the locally-decrypted tree (`listTree`).
import type { TreeNode } from './tree'
import type { Crumb } from '../../app/breadcrumb'
import { NodeKind } from '../../api/generated/content'

const byOrder = (a: TreeNode, b: TreeNode): number => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)

/** The children of `parentId` (null = the scope root), split into folders + Memories, each ordered. */
export function childrenOf(tree: TreeNode[], parentId: string | null): { folders: TreeNode[]; memories: TreeNode[] } {
  const kids = tree.filter((n) => (n.parentId ?? null) === parentId)
  return {
    folders: kids.filter((n) => n.kind === NodeKind.Folder).sort(byOrder),
    memories: kids.filter((n) => n.kind === NodeKind.Memory).sort(byOrder),
  }
}

/** The breadcrumb from the root down to `nodeId` (null = root): `[root, …ancestors, node]`. */
export function breadcrumbOf(tree: TreeNode[], nodeId: string | null, rootName: string): Crumb[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const trail: Crumb[] = []
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur) {
    trail.unshift({ id: cur.id, name: cur.name })
    cur = cur.parentId ? byId.get(cur.parentId) ?? null : null
  }
  trail.unshift({ id: null, name: rootName })
  return trail
}

/** A node by id (e.g. to title the folder/Memory currently open), or null. */
export function nodeById(tree: TreeNode[], nodeId: string | null): TreeNode | null {
  if (!nodeId) return null
  return tree.find((n) => n.id === nodeId) ?? null
}
