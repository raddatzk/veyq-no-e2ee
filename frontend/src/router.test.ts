import { describe, expect, it } from 'vitest'
import router from './router'
import { authState } from './authState'
import { serverState } from './serverState'

describe('router', () => {
  it('allows anonymous email confirmation links', async () => {
    authState.loading = false
    authState.isAuthenticated = false
    serverState.setupCompleted = true

    await router.push('/email/confirm#token-123')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('email-confirm')
  })
})
