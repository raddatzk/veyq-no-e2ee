import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { listTree, createFolder, createMemory, openMemory } from './tree'
import { contentApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { b64std } from '../../shared/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const scopeKey = v1.randomBytes(32)

function mockContent(over: Partial<Record<'createNode' | 'listNodes' | 'updateNode' | 'deleteNode', unknown>>) {
  vi.mocked(contentApi).mockReturnValue({
    createNode: vi.fn().mockResolvedValue({ nodeId: 'n1' }),
    listNodes: vi.fn().mockResolvedValue([]),
    updateNode: vi.fn(),
    deleteNode: vi.fn(),
    ...over,
  } as unknown as ReturnType<typeof contentApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('createMemory', () => {
  it('seals the name, blinds the slug, and wraps a K_mem that opens back (operator-blind)', async () => {
    const create = vi.fn().mockResolvedValue({ nodeId: 'mem-1' })
    mockContent({ createNode: create })

    const { nodeId, memoryKey } = await createMemory(SCOPE_ID, scopeKey, 'Italien 2026')
    expect(nodeId).toBe('mem-1')
    expect(memoryKey.length).toBe(32)

    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('MEMORY')
    // name round-trips under K_SC; the server only ever saw ciphertext
    expect(new TextDecoder().decode(v1.symUnwrap(scopeKey, fromB64(req.encryptedName), 'node-name'))).toBe('Italien 2026')
    // wrapped K_mem opens to exactly the returned key
    expect(Array.from(openMemory(scopeKey, { wrappedContentKey: req.wrappedContentKey })!)).toEqual(Array.from(memoryKey))
    // slug token = blinded MAC under K_SC over the root parent slot (the scope id) + the slug
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, SCOPE_ID, 'italien-2026')))
  })
})

describe('createFolder', () => {
  it('creates a keyless folder (no wrappedContentKey)', async () => {
    const create = vi.fn().mockResolvedValue({ nodeId: 'f1' })
    mockContent({ createNode: create })
    await createFolder(SCOPE_ID, scopeKey, 'Trips')
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('FOLDER')
    expect(req.wrappedContentKey).toBeUndefined()
  })
})

describe('listTree', () => {
  it('decrypts node names under K_SC', async () => {
    const encryptedName = b64std(v1.symWrap(scopeKey, v1.utf8('Trips'), 'node-name', v1.randomBytes(12)))
    mockContent({
      listNodes: vi.fn().mockResolvedValue([
        { id: 'f1', parentId: null, kind: 'FOLDER', encryptedName, slugToken: 'x', orderIndex: 0 },
      ]),
    })
    const tree = await listTree(SCOPE_ID, scopeKey)
    expect(tree).toHaveLength(1)
    expect(tree[0].name).toBe('Trips')
    expect(tree[0].parentId).toBeNull()
  })
})

function fromB64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
}
