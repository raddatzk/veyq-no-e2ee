// MemoryHome data orchestration: list the spaces the user can enter, open one (scope + content key),
// list a level of the tree with decrypted names, and lazily resolve a memory's cover image. All built on
// the existing E2E-tested slices (spaces / browse / media); the server stays blind to names and bytes.
import type { UnlockedSession } from '../account/login/login'
import { t } from '../../i18n/i18n'
import { getOrCreateContentKey, openScopeById, type OpenScope } from '../content/scopeContent'
import { openSealedName } from '../spaces/groups/groupSpaces'
import { decodeNode, listChildren, resolvePath, type MemoryNode } from '../memory/browse/browse'
import { listNodes } from '../memory/createTree/memoryTree'
import { listMySpaces, openSpace, type SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'
import { downloadBlob, listMedia } from '../uploads/media/media'

export type { SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'
export type { MemoryNode } from '../memory/browse/browse'

export type ActiveSpace = { entry: SpaceEntry; scope: OpenScope; contentKey: Uint8Array }
export type LevelEntry = { node: MemoryNode; name: string; slug: string }

/** Every space the user can enter (personal + tenants + groups), with group names decrypted for display. */
export async function loadSpaces(apiBase: string, session: UnlockedSession): Promise<SpaceEntry[]> {
  const spaces = await listMySpaces(apiBase, session)
  // Group names are sealed under the group content key — open each group scope to decrypt its name.
  await Promise.all(
    spaces
      .filter((s) => s.kind === 'GROUP' && s.sealedName)
      .map(async (s) => {
        try {
          const scope = await openScopeById(apiBase, session, s.scopeId)
          const ck = await getOrCreateContentKey(apiBase, session, scope)
          s.displayName = openSealedName(ck, s.sealedName!.bytes)
        } catch {
          s.displayName = '🔒'
        }
      }),
  )
  return spaces
}

/** Switch into a space: open its scope and content key (provisioning the key on first use). */
export async function enterSpace(apiBase: string, session: UnlockedSession, entry: SpaceEntry): Promise<ActiveSpace> {
  const scope = await openSpace(apiBase, session, entry)
  const contentKey = await getOrCreateContentKey(apiBase, session, scope)
  return { entry, scope, contentKey }
}

/** One tree level (root when parentId is omitted), with decrypted node names. */
export async function loadLevel(apiBase: string, session: UnlockedSession, active: ActiveSpace, parentId?: string): Promise<LevelEntry[]> {
  const nodes = await listChildren(apiBase, session, active.scope.scopeId, parentId)
  return nodes.map((node) => { const c = decodeNode(active.contentKey, node); return { node, name: c.name, slug: c.slug } })
}

/**
 * Resolve a slug path within the active space into the folder chain (the breadcrumb back-stack) plus a
 * trailing memory if the path ends on one. Used to open a deep link / bookmarked URL at the right place
 * with a correct back-stack (each folder is a crumb), not wherever the user happened to be.
 */
export async function resolveRoute(apiBase: string, session: UnlockedSession, active: ActiveSpace, segments: string[]): Promise<{ folders: LevelEntry[]; memory: LevelEntry | null }> {
  if (segments.length === 0) return { folders: [], memory: null }
  const chain = await resolvePath(apiBase, session, active.scope.scopeId, active.contentKey, segments)
  const entries = chain.map((node) => { const c = decodeNode(active.contentKey, node); return { node, name: c.name, slug: c.slug } })
  const last = entries[entries.length - 1]
  if (last && last.node.type === 'MEMORY') return { folders: entries.slice(0, -1), memory: last }
  return { folders: entries, memory: null }
}

/** Valid move targets for a node: the root + every folder that isn't the node or in its own subtree. */
export async function moveDestinations(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode): Promise<{ id: string; name: string }[]> {
  const all = await listNodes(apiBase, session, active.scope.scopeId)
  const root = active.scope.scopeId
  const childrenOf = (id: string) => all.filter((n) => (n.parentId ?? root) === id)
  const banned = new Set<string>([node.id])
  const stack = [node.id]
  while (stack.length) {
    const cur = stack.pop()!
    for (const c of childrenOf(cur)) if (!banned.has(c.id)) { banned.add(c.id); stack.push(c.id) }
  }
  const dests: { id: string; name: string }[] = [{ id: 'root', name: t('move.toRoot') }]
  for (const n of all) if (n.type === 'FOLDER' && !banned.has(n.id)) dests.push({ id: n.id, name: decodeNode(active.contentKey, n).name })
  return dests
}

/** Resolve a single node by id (with its decrypted name) — used to deep-link into a memory from a share. */
export async function loadMemoryById(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<LevelEntry | null> {
  const all = await listNodes(apiBase, session, active.scope.scopeId)
  const node = all.find((n) => n.id === nodeId)
  if (!node) return null
  const c = decodeNode(active.contentKey, node)
  return { node, name: c.name, slug: c.slug }
}

/** Build the node-id chain (root→…→nodeId) for a node, so a deep link can carry the full folder back-stack. */
export async function ancestorChain(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<LevelEntry[]> {
  const all = await listNodes(apiBase, session, active.scope.scopeId)
  const byId = new Map(all.map((n) => [n.id, n]))
  const root = active.scope.scopeId
  const chain: LevelEntry[] = []
  let cur = byId.get(nodeId)
  while (cur) {
    const c = decodeNode(active.contentKey, cur)
    chain.unshift({ node: cur, name: c.name, slug: c.slug })
    const parent = cur.parentId
    cur = parent && parent !== root ? byId.get(parent) : undefined
  }
  return chain
}

/** A memory's cover: the pinned cover media (or the first media) thumbnail, decrypted to an object URL. */
export async function loadCover(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode): Promise<string | null> {
  const media = await listMedia(apiBase, session, active.scope.scopeId, node.id)
  if (!media.length) return null
  const chosen = (node.coverMediaId ? media.find((m) => m.id === node.coverMediaId) : undefined) ?? media[0]
  const ref = chosen.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? chosen.original
  const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, ref)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: chosen.mimeType }))
}
