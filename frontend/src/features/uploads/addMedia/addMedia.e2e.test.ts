// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode } from '../../memory/createTree/memoryTree'
import { addMedia, listMedia, decodeBlob } from './addMedia'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('add-media E2E (web -> live backend): encrypted media into a Memory', () => {
  it('seals media + thumbnail under the content key, orders by takenAt, and round-trips the bytes', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)
    const memory = await createNode(apiBase, a, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    // two photos with non-ASCII / binary bytes, added out of takenAt order
    const photoLater = Uint8Array.from([0xff, 0xd8, 0xff, 0x00, 0x10, 0x20])
    const photoEarlier = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x01, 0x02])
    const thumb = Uint8Array.from([1, 2, 3, 4])
    await addMedia(apiBase, a, scope.scopeId, ck, memory.id, { bytes: photoLater, thumb, takenAt: '2026-03-15T12:00:00Z', mimeType: 'image/jpeg' })
    await addMedia(apiBase, a, scope.scopeId, ck, memory.id, { bytes: photoEarlier, takenAt: '2026-01-01T08:00:00Z', mimeType: 'image/png' })

    // the gallery is ordered by takenAt (earliest first); the server only held ciphertext
    const items = await listMedia(apiBase, a, scope.scopeId, memory.id)
    expect(items.length).toBe(2)
    expect(items.map((m) => m.mimeType)).toEqual(['image/png', 'image/jpeg'])
    expect(items[0].byteSize).toBe(photoEarlier.length)

    // the bytes decrypt back byte-identical, and the thumbnail too
    expect(Array.from(decodeBlob(ck, items[0].sealedBlob))).toEqual(Array.from(photoEarlier))
    expect(Array.from(decodeBlob(ck, items[1].sealedBlob))).toEqual(Array.from(photoLater))
    expect(Array.from(decodeBlob(ck, items[1].sealedThumb!))).toEqual(Array.from(thumb))
    expect(items[0].sealedThumb).toBeFalsy() // no thumbnail provided → absent/null
  })
})
