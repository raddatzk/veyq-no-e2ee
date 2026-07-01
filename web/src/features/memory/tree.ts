// en.memory.tree (web) — the encrypted Memory tree data layer. Operator-blind: node names are sealed under
// the scope content key K_SC, the slug token is a blinded keyed MAC under K_SC, and every content-bearing node
// (MEMORY or CHAPTER) carries a fresh random K_mem wrapped under its PARENT's content key — K_SC for a
// top-level Memory, the parent Memory's K_mem for a chapter (recursive K_mem, the share grain, ADR 0012 +
// cr-0060). Folders carry no key. The caller opens the scope first (features/spaces/scopes.openPersonalScope →
// K_SC) and passes K_SC in.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { contentApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { NodeKind, type NodeSummary } from '../../api/generated/content'

const NAME_CTX = 'node-name'
const KMEM_CTX = 'content-key'
const decoder = new TextDecoder()

/** A decrypted tree node for the UI. `name` is decrypted client-side; the server never saw it. */
export type TreeNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  orderIndex: number
  name: string
  wrappedContentKey: string | null
  /** Curated metadata (date now, place later) sealed under this node's `K_mem` (cr-0067); null when unset. */
  wrappedMeta: string | null
}

const GERMAN_FOLDING: Record<string, string> = { ä: 'ae', ö: 'oe', ü: 'ue', ß: 'ss' }

/**
 * URL-safe slug: deterministic, `[a-z0-9-]` only — the same name always yields the same slug (so the same
 * blinded `slugToken`) AND it's safe as a `#fragment` path segment (cr-0031). German folding + diacritic
 * stripping keep Latin names readable ("Müller" → "mueller", "Café" → "cafe"); a name that reduces to nothing
 * (e.g. emoji/CJK-only) falls back to a deterministic short token so siblings stay distinct and addressable.
 */
export function slugify(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[äöüß]/g, (c) => GERMAN_FOLDING[c])
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '') // drop combining diacritical marks (é → e)
    .replace(/[^a-z0-9]+/g, '-') // any run of non-alphanumerics → a single dash
    .replace(/^-+|-+$/g, '') // trim leading/trailing dashes
  return slug || `n${fnv1a(name)}`
}

/** Tiny deterministic 32-bit hash (FNV-1a) → base36, for the empty-slug fallback only. */
function fnv1a(value: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(36)
}

function sealName(scopeKey: Uint8Array, name: string): string {
  return b64std(v1.symWrap(scopeKey, v1.utf8(name), NAME_CTX, v1.randomBytes(12)))
}
function openName(scopeKey: Uint8Array, encryptedName: string): string {
  return decoder.decode(v1.symUnwrap(scopeKey, fromB64std(encryptedName), NAME_CTX))
}
function slugTokenFor(scopeKey: Uint8Array, parentSlot: string, name: string): string {
  return b64std(v1.slugToken(scopeKey, parentSlot, slugify(name)))
}

/** List the scope's tree with names decrypted under `scopeKey` (K_SC). */
export async function listTree(scopeId: string, scopeKey: Uint8Array): Promise<TreeNode[]> {
  const rows = await withRefresh(() => contentApi().listNodes({ scopeId }))
  return rows.map((r: NodeSummary) => ({
    id: r.id,
    parentId: r.parentId ?? null,
    kind: r.kind,
    orderIndex: r.orderIndex,
    name: openName(scopeKey, r.encryptedName),
    wrappedContentKey: r.wrappedContentKey ?? null,
    wrappedMeta: r.wrappedMeta ?? null,
  }))
}

/** Create a FOLDER (organization only — no content key). Returns its node id. */
export async function createFolder(
  scopeId: string, scopeKey: Uint8Array, name: string, parentId: string | null = null,
): Promise<string> {
  const parentSlot = parentId ?? scopeId
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        parentId: parentId ?? undefined,
        kind: NodeKind.Folder,
        encryptedName: sealName(scopeKey, name),
        slugToken: slugTokenFor(scopeKey, parentSlot, name),
        orderIndex: 0,
      },
    }),
  )
  return res.nodeId
}

/**
 * Create a content-bearing node (MEMORY or CHAPTER) — births a fresh random `K_mem`, wrapped under the
 * **parent's content key** `wrapKey` (recursive `K_mem`, ADR 0012): `K_SC` for a top-level Memory, the parent
 * Memory's `K_mem` for a chapter. The name + slug are always sealed under `K_SC` (`scopeKey`).
 */
async function createContentNode(
  scopeId: string, scopeKey: Uint8Array, wrapKey: Uint8Array, kind: NodeKind, name: string, parentId: string | null,
): Promise<{ nodeId: string; contentKey: Uint8Array }> {
  const parentSlot = parentId ?? scopeId
  const contentKey = v1.randomBytes(32)
  const wrappedContentKey = b64std(v1.symWrap(wrapKey, contentKey, KMEM_CTX, v1.randomBytes(12)))
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        parentId: parentId ?? undefined,
        kind,
        encryptedName: sealName(scopeKey, name),
        slugToken: slugTokenFor(scopeKey, parentSlot, name),
        orderIndex: 0,
        wrappedContentKey,
      },
    }),
  )
  return { nodeId: res.nodeId, contentKey }
}

/** Create a MEMORY — births a fresh random `K_mem` wrapped under `K_SC`. Returns the node id + the K_mem. */
export async function createMemory(
  scopeId: string, scopeKey: Uint8Array, name: string, parentId: string | null = null,
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const { nodeId, contentKey } = await createContentNode(scopeId, scopeKey, scopeKey, NodeKind.Memory, name, parentId)
  return { nodeId, memoryKey: contentKey }
}

/**
 * Create a CHAPTER inside a Memory (or another chapter) — births a fresh random `K_mem` wrapped under the
 * **parent node's `K_mem`** (`parentKey`), so the chapter is a sharing unit of its own (ADR 0012, cr-0060).
 * Returns the node id + the chapter's K_mem.
 */
export async function createChapter(
  scopeId: string, scopeKey: Uint8Array, parentKey: Uint8Array, name: string, parentId: string,
): Promise<{ nodeId: string; chapterKey: Uint8Array }> {
  const { nodeId, contentKey } = await createContentNode(scopeId, scopeKey, parentKey, NodeKind.Chapter, name, parentId)
  return { nodeId, chapterKey: contentKey }
}

/**
 * Open a content node's `K_mem` by unwrapping `node.wrappedContentKey` under its parent's content key
 * (`parentKey` = `K_SC` for a top-level Memory, the parent Memory's `K_mem` for a chapter). Null if it carries
 * no key (a folder). For a node at arbitrary depth, resolve `parentKey` with [nodeContentKey].
 */
export function openContentKey(parentKey: Uint8Array, node: { wrappedContentKey: string | null }): Uint8Array | null {
  if (!node.wrappedContentKey) return null
  return v1.symUnwrap(parentKey, fromB64std(node.wrappedContentKey), KMEM_CTX)
}

/** Back-compat alias: a top-level Memory's `K_mem` opens under `K_SC`. Prefer [openContentKey] / [nodeContentKey]. */
export const openMemory = openContentKey

/**
 * Resolve a content node's `K_mem` from `K_SC`, walking the content-bearing ancestors top-down: folders are
 * transparent (no key), so a Memory opens under `K_SC` and each nested chapter under its parent's `K_mem`.
 * Returns null if the node carries no key or any link in the chain can't be opened. (Recursive `K_mem`, ADR 0012.)
 */
export function nodeContentKey(scopeKey: Uint8Array, tree: TreeNode[], nodeId: string): Uint8Array | null {
  const byId = new Map(tree.map((n) => [n.id, n]))
  // collect the content-bearing chain from the node up to the root (nearest-last → reverse to top-down)
  const chain: TreeNode[] = []
  for (let cur = byId.get(nodeId); cur; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    if (cur.wrappedContentKey) chain.unshift(cur)
  }
  let key = scopeKey
  for (const node of chain) {
    const opened = openContentKey(key, node)
    if (!opened) return null
    key = opened
  }
  return chain.length ? key : null
}

/** Rename a node — re-seal the name + recompute the slug for the (unchanged) parent. */
export async function renameNode(
  scopeId: string, scopeKey: Uint8Array, node: { id: string; parentId: string | null }, newName: string,
): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  await withRefresh(() =>
    contentApi().updateNode({
      scopeId,
      nodeId: node.id,
      updateNodeRequest: {
        encryptedName: sealName(scopeKey, newName),
        slugToken: slugTokenFor(scopeKey, parentSlot, newName),
      },
    }),
  )
}

/** Delete a node and its subtree. */
export async function deleteNode(scopeId: string, nodeId: string): Promise<void> {
  await withRefresh(() => contentApi().deleteNode({ scopeId, nodeId }))
}
