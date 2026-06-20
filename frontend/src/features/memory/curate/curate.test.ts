// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createChild, isNodeNameConflict, moveNode, renameNode } from './curate'
import { resealNode, type MemoryNode } from '../createTree/memoryTree'
import type { ActiveSpace } from '../../home/home'
import type { UnlockedSession } from '../../account/login/login'
import { b64url } from '../../../shared/base64'

const apiBase = 'http://test'
const scopeId = 'scope-curate-1'
const contentKey = new Uint8Array(32)
const session = { sessionToken: 'tok' } as UnlockedSession
const active = { scope: { scopeId }, contentKey } as unknown as ActiveSpace

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) }
}

function conflict() {
  return { ok: false, status: 409, json: async () => ({ code: 'SLUG_EXISTS' }), text: async () => JSON.stringify({ code: 'SLUG_EXISTS' }) }
}

function node(id: string, name = 'Trip'): MemoryNode {
  return {
    id,
    parentId: null,
    scopeId,
    type: 'FOLDER',
    slugToken: 'x',
    orderIndex: 0,
    sealedContent: { suiteId: 'v1', bytes: b64url(resealNode(contentKey, { name, slug: 'trip' })) },
    createdAt: '2026-06-19T00:00:00Z',
  }
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('curate node naming', () => {
  it('does not create a numbered slug when a sibling already uses the name', async () => {
    const fetchMock = vi.fn(async () => conflict())
    vi.stubGlobal('fetch', fetchMock)

    await expect(createChild(apiBase, session, active, 'MEMORY', 'Trip', 0)).rejects.toSatisfy(isNodeNameConflict)

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not rename with a numbered suffix when the target name collides', async () => {
    const fetchMock = vi.fn(async () => conflict())
    vi.stubGlobal('fetch', fetchMock)

    await expect(renameNode(apiBase, session, active, node('n1'), 'Trip')).rejects.toSatisfy(isNodeNameConflict)

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not move with a numbered suffix when the destination already has that name', async () => {
    const fetchMock = vi.fn(async () => conflict())
    vi.stubGlobal('fetch', fetchMock)

    await expect(moveNode(apiBase, session, active, node('n1'), 'root')).rejects.toSatisfy(isNodeNameConflict)

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('still creates a node with the base slug when there is no collision', async () => {
    const created = node('n1', 'Trip')
    const fetchMock = vi.fn(async () => ok(created))
    vi.stubGlobal('fetch', fetchMock)

    await expect(createChild(apiBase, session, active, 'FOLDER', 'Trip', 0)).resolves.toEqual(created)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    const body = JSON.parse(init.body as string)
    expect(body.type).toBe('FOLDER')
    expect(body.orderIndex).toBe(0)
    expect(body.slugToken).toBeTruthy()
  })
})
