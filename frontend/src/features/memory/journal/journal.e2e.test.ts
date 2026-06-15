// @vitest-environment node
// Live journal round-trip against a running backend (opt-in via VEYQ_SEED=1): register → memory → seal a
// Markdown body via updateJournal → read it back via getJournal and assert the plaintext survives the
// client-seal ↔ server-store ↔ client-open trip. Exercises the exact modules the UI calls.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { getOrCreateContentKey, openPersonalScope } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { getJournal, updateJournal } from './journal'

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
    const saved = await updateJournal(apiBase, session, active, memory.id, md)
    expect(saved.updatedAt).toBeTruthy()

    const reread = await getJournal(apiBase, session, active, memory.id)
    expect(reread.markdown).toBe(md)

    // whole-document replace
    await updateJournal(apiBase, session, active, memory.id, 'Kürzer.')
    expect((await getJournal(apiBase, session, active, memory.id)).markdown).toBe('Kürzer.')
  }, 120000)
})
