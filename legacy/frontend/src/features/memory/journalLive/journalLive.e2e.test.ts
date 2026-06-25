// @vitest-environment node
// Live (VEYQ_E2E=1): two encrypted Yjs providers join the SAME journal room through the operator-blind
// relay and converge — proving the relay (auth + ciphertext broadcast), the AEAD wrapping, and the Yjs
// sync/awareness all work end-to-end. The relay never sees plaintext (it only forwards sealed bytes).
import { describe, it, expect } from 'vitest'
import * as Y from 'yjs'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { EncryptedRelayProvider } from './encryptedProvider'
import { fetchJournalLiveTicket } from './journalPersistence'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_E2E === '1' ? describe : describe.skip

async function waitFor(cond: () => boolean, ms = 6000) {
  const t0 = Date.now()
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error('timeout')
    await new Promise((r) => setTimeout(r, 50))
  }
}

run('live journal relay e2e', () => {
  it('two clients converge through the operator-blind relay; an unauthorized token is rejected', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'live@veyq.app', 'Live', pass)
    const session = await login(apiBase, 'live@veyq.app', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)
    const wsBase = apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
    // a fresh single-use ticket per (re)connect — the session token never rides the URL
    const urlFor = async () =>
      `${wsBase}/ws/journal/${scope.scopeId}/n1?ticket=${encodeURIComponent(await fetchJournalLiveTicket(apiBase, session))}`

    const dA = new Y.Doc(); const dB = new Y.Doc()
    const pA = new EncryptedRelayProvider(urlFor, dA, ck)
    const pB = new EncryptedRelayProvider(urlFor, dB, ck)
    await waitFor(() => pA.ws?.readyState === 1 && pB.ws?.readyState === 1)

    dA.getText('journal').insert(0, 'hallo welt')
    await waitFor(() => dB.getText('journal').toString() === 'hallo welt')
    expect(dB.getText('journal').toString()).toBe('hallo welt')

    // concurrent edits merge (CRDT): B appends, A prepends — both docs end identical
    dB.getText('journal').insert(dB.getText('journal').length, ' !')
    dA.getText('journal').insert(0, '> ')
    await waitFor(() => dA.getText('journal').toString() === dB.getText('journal').toString())
    expect(dA.getText('journal').toString()).toBe('> hallo welt !')

    pA.destroy(); pB.destroy()

    // an invalid/forged ticket is refused by the relay (the socket closes without joining)
    const bad = new WebSocket(`${wsBase}/ws/journal/${scope.scopeId}/n1?ticket=nope`)
    const closed = await new Promise<boolean>((res) => { bad.onclose = () => res(true); bad.onopen = () => bad.send(new Uint8Array([1, 2, 3])); setTimeout(() => res(bad.readyState === 3), 3000) })
    expect(closed).toBe(true)
  }, 30000)
})
