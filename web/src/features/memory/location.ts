// Fragment-based content navigation (cr-0031). The current Memory tree position lives in the URL **#fragment**
// — e.g. `#~me/reisen-2026/rom` — which the browser never sends to the server, so the operator-blind invariant
// holds even though the path carries human-readable slugs. Everything here is pure + client-side: the slug path
// is resolved against the locally-decrypted tree, and reverse-built from it. `~me` is the personal scope; the
// `@group` form is reserved for the (later) groups slice — parsed, but only `~me` resolves today.
import { slugify, type TreeNode } from './tree'

export const PERSONAL_SCOPE = '~me'

export type ContentLocation = { scope: string; segments: string[] }

/** Parse a `location.hash` into a scope token + slug segments. Empty/`#` → the personal scope root. */
export function parseHash(hash: string): ContentLocation {
  const parts = hash
    .replace(/^#/, '')
    .split('/')
    .filter(Boolean)
    .map(decodeURIComponent)
  if (parts.length === 0) return { scope: PERSONAL_SCOPE, segments: [] }
  const [head, ...rest] = parts
  // A leading scope token (`~me` / `@group`) selects the scope; anything else is a bare path under personal.
  if (head === PERSONAL_SCOPE || head.startsWith('@')) return { scope: head, segments: rest }
  return { scope: PERSONAL_SCOPE, segments: parts }
}

/** Build the fragment for a scope + slug path, e.g. `#~me/reisen-2026/rom`. */
export function buildHash(scope: string, segments: string[]): string {
  return `#${[scope, ...segments].map(encodeURIComponent).join('/')}`
}

/**
 * Resolve a slug path under the scope root to a node id, walking the tree level by level.
 * `null` = the scope root (no segments); a node id = the addressed node; `false` = a segment didn't match.
 */
export function resolveSlugPath(tree: TreeNode[], segments: string[]): string | null | false {
  let parentId: string | null = null
  for (const seg of segments) {
    const match = tree.find((n) => (n.parentId ?? null) === parentId && slugify(n.name) === seg)
    if (!match) return false
    parentId = match.id
  }
  return parentId
}

/** The slug path from the scope root down to `nodeId` (root = `[]`). */
export function slugPathOf(tree: TreeNode[], nodeId: string | null): string[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const segments: string[] = []
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur) {
    segments.unshift(slugify(cur.name))
    cur = cur.parentId ? byId.get(cur.parentId) ?? null : null
  }
  return segments
}

/** The personal-scope fragment for a node (`null` → the root `#~me`). */
export function hashForNode(tree: TreeNode[], nodeId: string | null): string {
  return buildHash(PERSONAL_SCOPE, slugPathOf(tree, nodeId))
}
