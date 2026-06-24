import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authFetch } from './sessionFetch'
import type { UnlockedSession } from '../../features/account/login/login'

describe('authFetch', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('refreshes an expired access token once and retries with the rotated token', async () => {
    const session = unlockedSession('old-token')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ sessionToken: 'new-token' }))
      .mockResolvedValueOnce(Response.json({ ok: true }))

    const response = await authFetch('', session, '/api/spaces')

    expect(response.ok).toBe(true)
    expect(session.sessionToken).toBe('new-token')
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/spaces', expect.objectContaining({
      headers: expect.any(Headers),
    }))
    expect((fetchMock.mock.calls[0][1]?.headers as Headers).get('authorization')).toBe('Bearer old-token')
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/refresh', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
    }))
    expect((fetchMock.mock.calls[2][1]?.headers as Headers).get('authorization')).toBe('Bearer new-token')
  })

  it('keeps the original 401 when refresh is not possible', async () => {
    const session = unlockedSession('old-token')
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }))

    const response = await authFetch('', session, '/api/spaces')

    expect(response.status).toBe(401)
    expect(session.sessionToken).toBe('old-token')
  })
})

function unlockedSession(sessionToken: string): UnlockedSession {
  return {
    sessionToken,
    user: { id: 'user-1', email: 'kevin@example.com', displayName: 'Kevin', serverRole: 'ServerOwner', username: 'kevin' },
    identity: {} as UnlockedSession['identity'],
    identityPublicKey: { suiteId: 'v1', kemPublicKey: '', signPublicKey: '' },
    masterKey: new Uint8Array(32),
  }
}
