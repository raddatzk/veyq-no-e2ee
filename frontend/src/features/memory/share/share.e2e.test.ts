// @vitest-environment node
// Live share loop (opt-in via VEYQ_SEED=1) against a real backend: an owner uploads media, then
//  (1) a VIEWER guest resolves the link, unwraps the content key from the fragment, and decrypts an
//      original byte-for-byte — with NO session;
//  (2) a CONTRIBUTOR guest adds a photo and writes the journal — also with no session.
// Proves the operator-blind public viewer + collect-uploads work end-to-end.
import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { loadShareJournalDoc } from '../journalLive/shareJournalLive'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { uploadMedia } from '../../uploads/media/media'
import { saveJournalMarkdown } from '../journalLive/journalPersistence'
import { listMySpaces } from '../../spaces/switchActiveSpace/switchActiveSpace'
import { createShare, downloadShareBlob, getShareJournal, listShareMedia, resolvePublicShare, SharePasswordRequired, updateShare, uploadShareMedia } from './share'

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
    const vShare = await createShare(apiBase, session, active, memory.id, { role: 'VIEWER' })
    const vKey = new URLSearchParams(vShare.link.slice(vShare.link.indexOf('#') + 1)).get('k')!
    expect(vShare.link).toContain(`/s/${vShare.shareId}#k=`)
    const vCtx = await resolvePublicShare(apiBase, vShare.shareId, vKey)
    expect(vCtx.rootName).toBe('Italien 2026')
    expect(vCtx.role).toBe('VIEWER')
    const vMedia = await listShareMedia(vCtx, vCtx.rootNodeId)
    expect(Array.from(await downloadShareBlob(vCtx, vMedia[0].original))).toEqual(Array.from(photo))

    // member-redirect predicate: the share's scope is one the (logged-in) owner already holds → full internal view
    const meta = await (await fetch(`${apiBase}/api/public/shares/${vShare.shareId}`)).json()
    expect((await listMySpaces(apiBase, session)).some((s) => s.scopeId === meta.scopeId)).toBe(true)

    // (2) CONTRIBUTOR — a guest adds a photo + writes the journal, sealed under the fragment content key
    const cShare = await createShare(apiBase, session, active, memory.id, { role: 'CONTRIBUTOR' })
    const cKey = new URLSearchParams(cShare.link.slice(cShare.link.indexOf('#') + 1)).get('k')!
    const cCtx = await resolvePublicShare(apiBase, cShare.shareId, cKey)
    expect(cCtx.role).toBe('CONTRIBUTOR')
    const contributed = Uint8Array.from({ length: 90 }, (_, i) => (i * 7 + 5) & 0xff)
    await uploadShareMedia(cCtx, { bytes: contributed, takenAt: '2026-05-01T10:00:00Z', mimeType: 'image/jpeg' })

    // the owner's gallery now holds both media; the owner's journal is READ-ONLY to the contributor
    expect(await listShareMedia(cCtx, cCtx.rootNodeId)).toHaveLength(2)
    await saveJournalMarkdown(apiBase, session, active.scope.scopeId, memory.id, active.contentKey, '# Unser Trip\n\nDanke fürs Mitmachen!')
    expect(await getShareJournal(cCtx)).toContain('Danke fürs Mitmachen')
    // operator-blind live read: a read-capable guest reconstructs the journal CRDT from the public store
    const guestDoc = new Y.Doc()
    await loadShareJournalDoc(apiBase, cCtx.shareId, cCtx.rootNodeId, cCtx.contentKey, guestDoc)
    expect(guestDoc.getText('journal').toString()).toContain('Danke fürs Mitmachen')
    guestDoc.destroy()

    // (3) UPLOADER — a blind drop-off: can add media, but reads are denied
    const uShare = await createShare(apiBase, session, active, memory.id, { role: 'UPLOADER' })
    const uKey = new URLSearchParams(uShare.link.slice(uShare.link.indexOf('#') + 1)).get('k')!
    const uCtx = await resolvePublicShare(apiBase, uShare.shareId, uKey)
    expect(uCtx.role).toBe('UPLOADER')
    expect(uCtx.rootName).toBe('') // blind drop-off: the server withholds the root node, so no album name leaks
    await uploadShareMedia(uCtx, { bytes: Uint8Array.from({ length: 50 }, (_, i) => i & 0xff), takenAt: '2026-06-01T10:00:00Z', mimeType: 'image/jpeg' })
    await expect(listShareMedia(uCtx, uCtx.rootNodeId)).rejects.toThrow() // no read rights

    // (4) PASSWORD — the link alone can't decrypt; the right password unwraps, the wrong one fails
    const pShare = await createShare(apiBase, session, active, memory.id, { role: 'VIEWER', password: 'hunter2', label: 'For grandma' })
    const pKey = new URLSearchParams(pShare.link.slice(pShare.link.indexOf('#') + 1)).get('k')!
    await expect(resolvePublicShare(apiBase, pShare.shareId, pKey)).rejects.toBeInstanceOf(SharePasswordRequired)
    await expect(resolvePublicShare(apiBase, pShare.shareId, pKey, 'wrong')).rejects.toMatchObject({ wrong: true })
    const pCtx = await resolvePublicShare(apiBase, pShare.shareId, pKey, 'hunter2')
    expect(pCtx.rootName).toBe('Italien 2026')

    // (5) EXPIRY — an already-expired link is denied
    const eShare = await createShare(apiBase, session, active, memory.id, { role: 'VIEWER', expiresAt: new Date(Date.now() - 1000).toISOString() })
    const eKey = new URLSearchParams(eShare.link.slice(eShare.link.indexOf('#') + 1)).get('k')!
    await expect(resolvePublicShare(apiBase, eShare.shareId, eKey)).rejects.toThrow()
    // edit: deactivate the expiry (+ relabel) → the same link works again
    await updateShare(apiBase, session, active, memory.id, eShare.shareId, { label: 'renewed' })
    expect((await resolvePublicShare(apiBase, eShare.shareId, eKey)).rootName).toBe('Italien 2026')

    // (6) NOTICE — a per-share notice rides the resolve (the viewer gates content behind accepting it)
    const nShare = await createShare(apiBase, session, active, memory.id, { role: 'VIEWER', noticeMarkdown: '## Bitte nur privat teilen' })
    const nKey = new URLSearchParams(nShare.link.slice(nShare.link.indexOf('#') + 1)).get('k')!
    const nCtx = await resolvePublicShare(apiBase, nShare.shareId, nKey)
    expect(nCtx.notices).toHaveLength(1)
    expect(nCtx.notices[0]).toMatchObject({ scope: 'SHARE', markdown: '## Bitte nur privat teilen' })
  }, 120000)
})
