// @vitest-environment node
// Live share loop (opt-in via VEYQ_SEED=1): owner uploads media to a memory, creates a read-only share, and
// a GUEST (no session) resolves the link, unwraps the content key from the fragment, and reads the media +
// decrypts an original — proving the operator-blind public viewer works end-to-end against a real backend.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { uploadMedia } from '../../uploads/media/media'
import { createShare, downloadShareBlob, listShareMedia, resolvePublicShare } from './share'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('share e2e', () => {
  it('a guest views a shared memory without an account', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'sharer@veyq.app', 'Sharer', pass)
    const session = await login(apiBase, 'sharer@veyq.app', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const active = { entry: { scopeId: scope.scopeId } as never, scope, contentKey: ck }
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    const photo = Uint8Array.from({ length: 120 }, (_, i) => (i * 11 + 1) & 0xff)
    await uploadMedia(apiBase, session, scope.scopeId, ck, memory.id, { bytes: photo, takenAt: '2026-04-01T09:00:00Z', mimeType: 'image/jpeg' })

    // owner creates the share; the link carries the share key in its fragment
    const { shareId, link } = await createShare(apiBase, session, active, memory.id)
    const shareKey = new URLSearchParams(link.slice(link.indexOf('#') + 1)).get('k')!
    expect(link).toContain(`/s/${shareId}#k=`)

    // a GUEST (no auth header anywhere) resolves + decrypts entirely client-side
    const ctx = await resolvePublicShare(apiBase, shareId, shareKey)
    expect(ctx.rootName).toBe('Italien 2026')
    const media = await listShareMedia(ctx, ctx.rootNodeId)
    expect(media).toHaveLength(1)
    const bytes = await downloadShareBlob(ctx, media[0].original)
    expect(Array.from(bytes)).toEqual(Array.from(photo)) // the guest decrypts the original byte-for-byte
  }, 120000)
})
