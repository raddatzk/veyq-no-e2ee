// @vitest-environment node
// Live journal round-trip against a running backend (opt-in via VEYQ_SEED=1): register → memory → write a
// Markdown body via the CRDT whole-doc save (compaction) → read it back via getJournal (the Markdown reader
// projection) and assert the plaintext survives the client-seal ↔ server-store ↔ client-open trip.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { getJournal } from './journal'
import { saveJournalMarkdown } from '../journalLive/journalPersistence'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip

run('journal e2e', () => {
  it('seals, stores and reads back a journal body', async () => {
    const pass = 'correct horse battery staple'
    const email = `journal-${Date.now()}@veyq.app`
    const { emergencyKit } = await registerServerOwner(apiBase, email, 'Kevin', pass)
    const session = await login(apiBase, email, pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const active = { entry: { scopeId: scope.scopeId } as never, scope, contentKey: ck }
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    // a fresh memory has no journal
    expect((await getJournal(apiBase, session, active, memory.id)).markdown).toBe('')

    const md = '# Tag 1\n\nWir kamen in **Rom** an.\n\n- Kolosseum\n- Pantheon'
    await saveJournalMarkdown(apiBase, session, scope.scopeId, memory.id, ck, md)

    const reread = await getJournal(apiBase, session, active, memory.id)
    expect(reread.markdown).toBe(md)
    expect(reread.updatedAt).toBeTruthy()

    // whole-document replace
    await saveJournalMarkdown(apiBase, session, scope.scopeId, memory.id, ck, 'Kürzer.')
    expect((await getJournal(apiBase, session, active, memory.id)).markdown).toBe('Kürzer.')
  }, 120000)
})
