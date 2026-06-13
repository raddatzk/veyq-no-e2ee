// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../admin/setupServer/setupServer'
import { login } from '../account/login/login'
import { openPersonalScope, getOrCreateContentKey, writeItem, readItems } from './scopeContent'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('scope content E2E (web -> live backend)', () => {
  it('writes and reads encrypted content under the personal scope; the server stays blind', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'owner@example.com', 'Owner', pass)
    const session = await login(apiBase, 'owner@example.com', pass, emergencyKit.secretKey)

    const scope = await openPersonalScope(apiBase, session)
    const ck = await getOrCreateContentKey(apiBase, session, scope)

    const secret = 'Italien 2024 — Tag 1'
    await writeItem(apiBase, session, scope, ck, secret)
    await writeItem(apiBase, session, scope, ck, 'second note')

    // round-trip: both items decrypt back to their plaintext
    const items = await readItems(apiBase, session, scope, ck)
    expect(items).toContain(secret)
    expect(items).toContain('second note')

    // re-opening from a fresh login recovers the same content (the content key is stable + scope-wrapped)
    const session2 = await login(apiBase, 'owner@example.com', pass, emergencyKit.secretKey)
    const scope2 = await openPersonalScope(apiBase, session2)
    const ck2 = await getOrCreateContentKey(apiBase, session2, scope2)
    expect(await readItems(apiBase, session2, scope2, ck2)).toContain(secret)

    // blindness: the server's stored ciphertext does not contain the plaintext
    const raw = await (await fetch(`${apiBase}/api/scopes/${scope.scopeId}/items`, { headers: { authorization: `Bearer ${session.sessionToken}` } })).json()
    for (const it of raw.items) {
      const dec = new TextDecoder('utf-8', { fatal: false }).decode(fromB64url(it.sealedContent.bytes))
      expect(dec).not.toContain('Italien')
    }
  })
})
