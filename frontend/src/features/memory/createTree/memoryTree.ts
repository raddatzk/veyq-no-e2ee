// uc.memory.create-tree (web) — build the encrypted Memory tree: Folders, Memories and Chapters under a
// scope. The name/title is sealed under the scope content key; the slug becomes a blinded slug token
// (AD-26) so the server enforces per-parent uniqueness without ever seeing the name. The server holds only
// the ID graph, types, tokens, order and ciphertext; the client reconstructs and decrypts locally.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}
const EMPTY = new Uint8Array(0)

export type NodeType = 'FOLDER' | 'MEMORY' | 'CHAPTER'
export type MemoryNode = {
  id: string
  parentId: string | null
  scopeId: string
  type: NodeType
  slugToken: string
  orderIndex: number
  sealedContent: { suiteId: string; bytes: string }
  createdAt: string
}

export type CreateNode = { type: NodeType; name: string; slug: string; orderIndex: number; parentId?: string }

/** Create one node: the name is sealed under the content key, the slug becomes a blinded token. */
export async function createNode(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string,
  contentKey: Uint8Array,
  node: CreateNode,
): Promise<MemoryNode> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(node.name), EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({
      ...(node.parentId ? { parentId: node.parentId } : {}),
      type: node.type,
      slugToken: b64url(v1.slugToken(contentKey, node.slug)),
      orderIndex: node.orderIndex,
      sealedContent: { suiteId: 'v1', bytes: b64url(sealed) },
    }),
  })
  if (!res.ok) throw new Error(`create node failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** List the scope's node graph (flat). The client reconstructs the tree + decrypts names locally. */
export async function listNodes(apiBase: string, session: UnlockedSession, scopeId: string): Promise<MemoryNode[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`list nodes failed: ${res.status} ${await res.text()}`)
  return (await res.json()).nodes
}

/** Decrypt a node's sealed name with the scope content key. */
export function decodeNodeName(contentKey: Uint8Array, node: MemoryNode): string {
  const sealed = fromB64url(node.sealedContent.bytes)
  return new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
}

/** The blinded slug token for a slug under a scope's content key (matches what the server stored). */
export function slugTokenFor(contentKey: Uint8Array, slug: string): string {
  return b64url(v1.slugToken(contentKey, slug))
}
