import { describe, expect, it } from 'vitest'
import router from './router'
import { authState } from './authState'
import { serverState } from './serverState'

describe('router', () => {
  it('redirects email confirmation to login with return path when anonymous', async () => {
    authState.loading = false
    authState.isAuthenticated = false
    serverState.setupCompleted = true

    await router.push('/email/confirm/token-123')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/email/confirm/token-123')
  })
})
