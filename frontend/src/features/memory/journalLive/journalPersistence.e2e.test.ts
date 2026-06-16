// @vitest-environment node
// Live (VEYQ_E2E=1): the operator-blind CRDT journal store. Proves NON-OVERLAPPING sessions converge — the
// thing the retired plaintext sidecar did, now without the server ever decrypting. Session A appends an
// increment; a fresh session B reconstructs A's text from the store, appends, and compacts (snapshot +
// Markdown projection, pruning the log); a third session reconstructs the compacted state from the snapshot
// alone; and the Markdown read projection matches.
import { describe, it, expect } from 'vitest'
import * as Y from 'yjs'
import * as v1 from '../../../shared/crypto/v1'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode } from '../createTree/memoryTree'
import { appendJournalUpdate, compactJournal, loadJournalDoc } from './journalPersistence'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_E2E === '1' ? describe : describe.skip

const EMPTY = new Uint8Array(0)
function fromB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}

run('operator-blind journal persistence e2e', () => {
  it('non-overlapping sessions converge via snapshot + increments; markdown projection follows', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'jp@veyq.app', 'JP', pass)
    const session = await login(apiBase, 'jp@veyq.app', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: 'Trip', slug: 'trip', orderIndex: 0 })
    const args = [apiBase, session, scope.scopeId, memory.id, ck] as const

    // Session A: append one increment (no compaction yet)
    const dA = new Y.Doc()
    await loadJournalDoc(...args, dA)
    dA.getText('journal').insert(0, 'hello')
    const seqA = await appendJournalUpdate(...args, Y.encodeStateAsUpdate(dA))
    expect(seqA).toBe(1)

    // Session B (fresh, no relay overlap): reconstructs A's text from the increment alone
    const dB = new Y.Doc()
    const maxB = await loadJournalDoc(...args, dB)
    expect(dB.getText('journal').toString()).toBe('hello')
    expect(maxB).toBe(1)

    // Session B appends, then compacts — writes a snapshot + the Markdown projection and prunes the log
    const sv = Y.encodeStateVector(dB)
    dB.getText('journal').insert(dB.getText('journal').length, ' world')
    const seqB = await appendJournalUpdate(...args, Y.encodeStateAsUpdate(dB, sv))
    await compactJournal(...args, dB, dB.getText('journal').toString(), seqB)

    // Session C reconstructs the compacted state from the SNAPSHOT alone (the log was pruned)
    const dC = new Y.Doc()
    await loadJournalDoc(...args, dC)
    expect(dC.getText('journal').toString()).toBe('hello world')

    // the live state now carries a snapshot and an empty (pruned) update log
    const live = await (await fetch(`${apiBase}/api/scopes/${scope.scopeId}/nodes/${memory.id}/journal/live`, {
      headers: { authorization: `Bearer ${session.sessionToken}` },
    })).json()
    expect(live.snapshot).toBeTruthy()
    expect(live.updates.length).toBe(0)
    expect(Number(live.baseSeq)).toBe(seqB)

    // the Markdown read projection (detail view / share) matches, written at compaction
    const md = await (await fetch(`${apiBase}/api/scopes/${scope.scopeId}/nodes/${memory.id}/journal`, {
      headers: { authorization: `Bearer ${session.sessionToken}` },
    })).json()
    const sealed = fromB64url(md.sealedContent.bytes)
    expect(new TextDecoder().decode(v1.aeadOpen(ck, sealed.slice(0, 12), sealed.slice(12), EMPTY))).toBe('hello world')
  }, 120000)
})
