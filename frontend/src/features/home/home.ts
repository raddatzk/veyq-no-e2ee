// MemoryHome data orchestration: list the spaces the user can enter, open one (scope + content key),
// list a level of the tree with decrypted names, and lazily resolve a memory's cover image. All built on
// the existing E2E-tested slices (spaces / browse / media); the server stays blind to names and bytes.
import type { UnlockedSession } from '../account/login/login'
import { getOrCreateContentKey, type OpenScope } from '../content/scopeContent'
import { decodeNode, listChildren, type MemoryNode } from '../memory/browse/browse'
import { listMySpaces, openSpace, type SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'
import { downloadBlob, listMedia } from '../uploads/media/media'

export type { SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'
export type { MemoryNode } from '../memory/browse/browse'

export type ActiveSpace = { entry: SpaceEntry; scope: OpenScope; contentKey: Uint8Array }
export type LevelEntry = { node: MemoryNode; name: string }

/** Every space the user can enter (personal + groups). */
export function loadSpaces(apiBase: string, session: UnlockedSession): Promise<SpaceEntry[]> {
  return listMySpaces(apiBase, session)
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
  return nodes.map((node) => ({ node, name: decodeNode(active.contentKey, node).name }))
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
