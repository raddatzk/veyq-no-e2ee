// @vitest-environment node
// Live GPX track round-trip (opt-in via VEYQ_SEED=1): seal a route, list + decrypt it, delete it — proving
// the client-seal ↔ server-store ↔ client-open trip preserves the route and the server stays blind.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { createTrack, deleteTrack, getTracks } from './track'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('track e2e', () => {
  it('seals, lists and deletes a GPX route', async () => {
    const pass = 'correct horse battery staple'
    const email = `track-${Date.now()}@veyq.app`
    const { emergencyKit } = await registerServerOwner(apiBase, email, 'Kevin', pass)
    const session = await login(apiBase, email, pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const active = { entry: { scopeId: scope.scopeId } as never, scope, contentKey: ck }
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Rom', slug: 'rom', orderIndex: 0 })

    expect(await getTracks(apiBase, session, active, memory.id)).toHaveLength(0)

    const route = { name: 'Colosseum Loop', points: [{ lat: 41.8902, lng: 12.4922 }, { lat: 41.891, lng: 12.493 }] }
    await createTrack(apiBase, session, active, memory.id, route)

    const tracks = await getTracks(apiBase, session, active, memory.id)
    expect(tracks).toHaveLength(1)
    expect(tracks[0].name).toBe('Colosseum Loop')
    expect(tracks[0].points).toHaveLength(2)
    expect(tracks[0].points[0].lat).toBeCloseTo(41.8902, 4)

    await deleteTrack(apiBase, session, active, memory.id, tracks[0].id)
    expect(await getTracks(apiBase, session, active, memory.id)).toHaveLength(0)
  }, 120000)
})
