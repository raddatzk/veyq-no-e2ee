// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { listChildren, resolvePath, decodeNode } from './browse'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('browse E2E (web -> live backend): resolve a clean slug path client-side', () => {
  it('walks the tree level by level and resolves the type of each path segment', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)

    // /urlaube (folder) -> /urlaube/italien-2026 (memory) -> /urlaube/italien-2026/tag-1 (chapter)
    const folder = await createNode(apiBase, a, scope.scopeId, ck, { type: 'FOLDER', name: 'Urlaube', slug: 'urlaube', orderIndex: 0 })
    const memory = await createNode(apiBase, a, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0, parentId: folder.id })
    await createNode(apiBase, a, scope.scopeId, ck, { type: 'CHAPTER', name: 'Tag 1', slug: 'tag-1', orderIndex: 0, parentId: memory.id })
    // a second root folder, to prove level listing returns only the right level
    await createNode(apiBase, a, scope.scopeId, ck, { type: 'FOLDER', name: 'Arbeit', slug: 'arbeit', orderIndex: 1 })

    // level listing: the root has two folders; the folder has one memory
    const roots = await listChildren(apiBase, a, scope.scopeId)
    expect(roots.length).toBe(2)
    expect(roots.map((n) => decodeNode(ck, n).name).sort()).toEqual(['Arbeit', 'Urlaube'])
    const folderChildren = await listChildren(apiBase, a, scope.scopeId, folder.id)
    expect(folderChildren.length).toBe(1)

    // resolve the clean path — the URL carried no types, but each resolved node knows its own
    const chain = await resolvePath(apiBase, a, scope.scopeId, ck, ['urlaube', 'italien-2026', 'tag-1'])
    expect(chain.map((n) => n.type)).toEqual(['FOLDER', 'MEMORY', 'CHAPTER'])
    expect(chain.map((n) => decodeNode(ck, n).name)).toEqual(['Urlaube', 'Italien 2026', 'Tag 1'])
    // the same leaf slug under a different parent is a different node — resolution is parent-scoped
    expect(chain[2].id).not.toBe(folder.id)

    // a non-existent segment fails
    await expect(resolvePath(apiBase, a, scope.scopeId, ck, ['urlaube', 'does-not-exist'])).rejects.toThrow()
  })
})
