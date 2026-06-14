// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode, listNodes, decodeNodeName, slugTokenFor } from './memoryTree'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('create-tree E2E (web -> live backend): an encrypted Folder/Memory/Chapter tree', () => {
  it('builds the tree, the server stays blind to names, and the client reconstructs it', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)

    // /urlaube (folder) -> /urlaube/italien-2026 (memory) -> /urlaube/italien-2026/tag-1 (chapter)
    const folder = await createNode(apiBase, a, scope.scopeId, ck, { type: 'FOLDER', name: 'Urlaube', slug: 'urlaube', orderIndex: 0 })
    const memory = await createNode(apiBase, a, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0, parentId: folder.id })
    const chapter = await createNode(apiBase, a, scope.scopeId, ck, { type: 'CHAPTER', name: 'Tag 1', slug: 'tag-1', orderIndex: 0, parentId: memory.id })

    expect(folder.parentId).toBeNull()
    expect(memory.parentId).toBe(folder.id)
    expect(chapter.parentId).toBe(memory.id)

    // the stored slug token is the deterministic blinded token — the server never saw "urlaube"
    expect(folder.slugToken).toBe(slugTokenFor(ck, 'urlaube'))

    // the client fetches the flat graph and reconstructs names + structure locally
    const nodes = await listNodes(apiBase, a, scope.scopeId)
    expect(nodes.length).toBe(3)
    const byId = new Map(nodes.map((n) => [n.id, n]))
    expect(decodeNodeName(ck, byId.get(folder.id)!)).toBe('Urlaube')
    expect(decodeNodeName(ck, byId.get(memory.id)!)).toBe('Italien 2026')
    expect(decodeNodeName(ck, byId.get(chapter.id)!)).toBe('Tag 1')
    // walk the path /urlaube/italien-2026/tag-1 by following parent edges
    expect(byId.get(chapter.id)!.parentId).toBe(memory.id)
    expect(byId.get(memory.id)!.parentId).toBe(folder.id)
    expect(byId.get(folder.id)!.parentId).toBeNull()

    // invariants enforced server-side: a duplicate sibling slug and a folder-in-a-memory are rejected
    await expect(createNode(apiBase, a, scope.scopeId, ck, { type: 'FOLDER', name: 'Urlaube 2', slug: 'urlaube', orderIndex: 1 })).rejects.toThrow()
    await expect(createNode(apiBase, a, scope.scopeId, ck, { type: 'FOLDER', name: 'Nope', slug: 'nope', orderIndex: 1, parentId: memory.id })).rejects.toThrow()
  })
})
