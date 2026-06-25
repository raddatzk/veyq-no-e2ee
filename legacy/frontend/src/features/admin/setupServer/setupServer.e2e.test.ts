// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from './setupServer'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
// Real end-to-end against a running backend; opt-in so plain `npm test` doesn't require it.
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('setup-server E2E (web v1 crypto -> live backend)', () => {
  it('creates the first E2EE user end-to-end', async () => {
    const status0 = await (await fetch(`${apiBase}/api/setup`)).json()
    expect(status0.setupRequired).toBe(true)

    const { user, emergencyKit } = await registerServerOwner(
      apiBase,
      'owner@example.com',
      'Owner',
      'correct horse battery staple',
    )
    expect(user.email).toBe('owner@example.com')
    expect(user.serverRole).toBe('ServerOwner')
    expect(emergencyKit.secretKey.length).toBeGreaterThan(0)

    const status1 = await (await fetch(`${apiBase}/api/setup`)).json()
    expect(status1.setupRequired).toBe(false)
  })
})
