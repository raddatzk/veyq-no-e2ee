// @vitest-environment node
// The per-scope node-graph cache and its invalidation contract: a burst of reads hits the server once, and
// every node mutation (create/rename/delete, all routed through curate) drops the cache so the next read is
// fresh. fetch is mocked so this is deterministic and backend-free — the e2e suites cover the wire format.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listNodesCached, invalidateNodeGraph } from './nodeGraph'
import { createChild, renameNode, deleteNode } from '../memory/curate/curate'
import type { ActiveSpace } from './home'
import type { MemoryNode } from '../memory/createTree/memoryTree'
import type { UnlockedSession } from '../account/login/login'

const apiBase = 'http://test'
const scopeId = 'scope-cache-1'
const session = { sessionToken: 'tok' } as UnlockedSession
const active = { scope: { scopeId }, contentKey: new Uint8Array(32) } as unknown as ActiveSpace

let listCalls = 0
let nodes: MemoryNode[]

function res(body: unknown) {
  return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) }
}

beforeEach(() => {
  invalidateNodeGraph(scopeId)
  listCalls = 0
  nodes = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
      const method = init?.method ?? 'GET'
      const nodesUrl = url.endsWith(`/scopes/${scopeId}/nodes`)
      if (nodesUrl && method === 'GET') { listCalls++; return res({ nodes }) }
      if (nodesUrl && method === 'POST') {
        const node = { id: `n${nodes.length + 1}`, parentId: null, scopeId, type: 'FOLDER', slugToken: 'x', orderIndex: 0, sealedContent: JSON.parse(init!.body!).sealedContent, createdAt: '2026' } as MemoryNode
        nodes.push(node)
        return res(node)
      }
      if (method === 'PATCH') return res(nodes[0])
      if (method === 'DELETE') { nodes = nodes.slice(1); return res({}) }
      throw new Error(`unexpected ${method} ${url}`)
    }),
  )
})

describe('node-graph per-scope cache', () => {
  it('serves a burst of reads from one fetch', async () => {
    await listNodesCached(apiBase, session, scopeId)
    await listNodesCached(apiBase, session, scopeId)
    expect(listCalls).toBe(1)
  })

  it('refetches after an explicit invalidate', async () => {
    await listNodesCached(apiBase, session, scopeId)
    invalidateNodeGraph(scopeId)
    await listNodesCached(apiBase, session, scopeId)
    expect(listCalls).toBe(2)
  })

  it('createChild drops the cache so the next read sees the new node', async () => {
    expect((await listNodesCached(apiBase, session, scopeId)).length).toBe(0)
    await createChild(apiBase, session, active, 'FOLDER', 'Alpha', 0)
    const after = await listNodesCached(apiBase, session, scopeId)
    expect(listCalls).toBe(2) // the create invalidated → a second fetch, not the stale empty cache
    expect(after.length).toBe(1)
  })

  it('renameNode drops the cache', async () => {
    await createChild(apiBase, session, active, 'FOLDER', 'Alpha', 0)
    await listNodesCached(apiBase, session, scopeId)
    const before = listCalls
    await renameNode(apiBase, session, active, { id: 'n1', parentId: null } as MemoryNode, 'Renamed')
    await listNodesCached(apiBase, session, scopeId)
    expect(listCalls).toBe(before + 1)
  })

  it('deleteNode drops the cache', async () => {
    await createChild(apiBase, session, active, 'FOLDER', 'Alpha', 0)
    await listNodesCached(apiBase, session, scopeId)
    const before = listCalls
    await deleteNode(apiBase, session, active, 'n1')
    expect((await listNodesCached(apiBase, session, scopeId)).length).toBe(0)
    expect(listCalls).toBe(before + 1)
  })
})
