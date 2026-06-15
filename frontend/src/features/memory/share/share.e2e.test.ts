// @vitest-environment node
// Live share loop (opt-in via VEYQ_SEED=1) against a real backend: an owner uploads media, then
//  (1) a VIEWER guest resolves the link, unwraps the content key from the fragment, and decrypts an
//      original byte-for-byte — with NO session;
//  (2) a CONTRIBUTOR guest adds a photo and writes the journal — also with no session.
// Proves the operator-blind public viewer + collect-uploads work end-to-end.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { uploadMedia } from '../../uploads/media/media'
import { createShare, downloadShareBlob, getShareJournal, listShareMedia, resolvePublicShare, updateShareJournal, uploadShareMedia } from './share'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('share e2e', () => {
  it('viewer reads + contributor adds, both without an account', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'sharer@veyq.app', 'Sharer', pass)
    const session = await login(apiBase, 'sharer@veyq.app', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const active = { entry: { scopeId: scope.scopeId } as never, scope, contentKey: ck }
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    const photo = Uint8Array.from({ length: 120 }, (_, i) => (i * 11 + 1) & 0xff)
    await uploadMedia(apiBase, session, scope.scopeId, ck, memory.id, { bytes: photo, takenAt: '2026-04-01T09:00:00Z', mimeType: 'image/jpeg' })

    // (1) VIEWER — a guest decrypts the original entirely client-side, no auth anywhere
    const vShare = await createShare(apiBase, session, active, memory.id, 'VIEWER')
    const vKey = new URLSearchParams(vShare.link.slice(vShare.link.indexOf('#') + 1)).get('k')!
    expect(vShare.link).toContain(`/s/${vShare.shareId}#k=`)
    const vCtx = await resolvePublicShare(apiBase, vShare.shareId, vKey)
    expect(vCtx.rootName).toBe('Italien 2026')
    expect(vCtx.role).toBe('VIEWER')
    const vMedia = await listShareMedia(vCtx, vCtx.rootNodeId)
    expect(Array.from(await downloadShareBlob(vCtx, vMedia[0].original))).toEqual(Array.from(photo))

    // (2) CONTRIBUTOR — a guest adds a photo + writes the journal, sealed under the fragment content key
    const cShare = await createShare(apiBase, session, active, memory.id, 'CONTRIBUTOR')
    const cKey = new URLSearchParams(cShare.link.slice(cShare.link.indexOf('#') + 1)).get('k')!
    const cCtx = await resolvePublicShare(apiBase, cShare.shareId, cKey)
    expect(cCtx.role).toBe('CONTRIBUTOR')
    const contributed = Uint8Array.from({ length: 90 }, (_, i) => (i * 7 + 5) & 0xff)
    await uploadShareMedia(cCtx, { bytes: contributed, takenAt: '2026-05-01T10:00:00Z', mimeType: 'image/jpeg' })
    await updateShareJournal(cCtx, '# Unser Trip\n\nDanke fürs Teilen!')

    // the owner's gallery now holds both media, and the guest's journal round-trips
    const all = await listShareMedia(cCtx, cCtx.rootNodeId)
    expect(all).toHaveLength(2)
    expect(await getShareJournal(cCtx)).toContain('Danke fürs Teilen')
  }, 120000)
})
