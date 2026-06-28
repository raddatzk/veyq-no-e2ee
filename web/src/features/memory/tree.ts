// en.memory.tree (web) — the encrypted Memory tree data layer. Operator-blind: node names are sealed under
// the scope content key K_SC, the slug token is a blinded keyed MAC under K_SC, and a Memory's K_mem is a
// fresh random key wrapped under K_SC (the share grain, ADR 0012). The caller opens the scope first (see
// features/spaces/scopes.openPersonalScope → K_SC) and passes K_SC in.
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
}

/** Minimal slug: NFC, lowercased, whitespace → "-". Kept deterministic so the same name → the same token. */
function slugify(name: string): string {
  return name.normalize('NFC').toLowerCase().trim().replace(/\s+/g, '-')
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

/** Create a MEMORY — births a fresh random `K_mem` wrapped under `K_SC`. Returns the node id + the K_mem. */
export async function createMemory(
  scopeId: string, scopeKey: Uint8Array, name: string, parentId: string | null = null,
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const parentSlot = parentId ?? scopeId
  const memoryKey = v1.randomBytes(32)
  const wrappedContentKey = b64std(v1.symWrap(scopeKey, memoryKey, KMEM_CTX, v1.randomBytes(12)))
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        parentId: parentId ?? undefined,
        kind: NodeKind.Memory,
        encryptedName: sealName(scopeKey, name),
        slugToken: slugTokenFor(scopeKey, parentSlot, name),
        orderIndex: 0,
        wrappedContentKey,
      },
    }),
  )
  return { nodeId: res.nodeId, memoryKey }
}

/** Open a Memory's content key (`K_mem`) from its node, or null if it carries none / not a Memory. */
export function openMemory(scopeKey: Uint8Array, node: { wrappedContentKey: string | null }): Uint8Array | null {
  if (!node.wrappedContentKey) return null
  return v1.symUnwrap(scopeKey, fromB64std(node.wrappedContentKey), KMEM_CTX)
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
