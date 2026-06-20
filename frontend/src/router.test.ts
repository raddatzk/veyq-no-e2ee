import { beforeEach, describe, expect, it } from 'vitest'
import router from './router'
import { serverState } from './serverState'
import { setUnlocked } from './app/session'

describe('router guard (E2EE)', () => {
  beforeEach(() => setUnlocked(null)) // locked by default; no cookie session anymore

  it('forces setup when the instance is not set up yet', async () => {
    serverState.setupCompleted = false
    await router.push('/some/memory')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('setup')
  })

  it('sends a locked visitor to login on a protected route', async () => {
    serverState.setupCompleted = true
    await router.push('/urlaube/italien')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('keeps public routes reachable while locked', async () => {
    serverState.setupCompleted = true
    await router.push('/login')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('routes an invite-fragment link to the accept view', async () => {
    serverState.setupCompleted = true
    await router.push('/#invite=abc&s=secret&k=tempkey')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('invite')
  })
})
