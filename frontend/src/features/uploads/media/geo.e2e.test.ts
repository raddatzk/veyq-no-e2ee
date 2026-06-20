// @vitest-environment node
// Live geo round-trip (opt-in via VEYQ_SEED=1): upload media carrying a GPS location, then list + decrypt
// and assert lat/lng survive the client-seal ↔ server-store ↔ client-open trip (the server stays blind).
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../../memory/createTree/memoryTree'
import { decodeMediaMeta, listMedia, uploadMedia } from './media'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('media geo e2e', () => {
  it('seals + reads back GPS location', async () => {
    const pass = 'correct horse battery staple'
    const email = `geo-${Date.now()}@veyq.app`
    const { emergencyKit } = await registerServerOwner(apiBase, email, 'Kevin', pass)
    const session = await login(apiBase, email, pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Rom', slug: 'rom', orderIndex: 0 })

    const bytes = Uint8Array.from({ length: 64 }, (_, i) => (i * 7) & 0xff)
    await uploadMedia(apiBase, session, scope.scopeId, ck, memory.id, { bytes, takenAt: '2026-04-01T09:00:00Z', mimeType: 'image/jpeg', lat: 41.8902, lng: 12.4922 })

    const media = await listMedia(apiBase, session, scope.scopeId, memory.id)
    expect(media).toHaveLength(1)
    const meta = decodeMediaMeta(ck, media[0])
    expect(meta.takenAt).toBe('2026-04-01T09:00:00Z')
    expect(meta.lat).toBeCloseTo(41.8902, 4)
    expect(meta.lng).toBeCloseTo(12.4922, 4)
  }, 120000)
})
