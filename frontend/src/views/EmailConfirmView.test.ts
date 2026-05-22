import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import EmailConfirmView from './EmailConfirmView.vue'
import { tenants } from '../api/tenantClient'
import { authState } from '../authState'
import { confirmMyEmailChange } from '../features/settings/api'

vi.mock('../api/tenantClient', () => ({
  tenants: vi.fn()
}))

vi.mock('../features/settings/api', () => ({
  confirmMyEmailChange: vi.fn()
}))

const confirmMyEmailChangeMock = vi.mocked(confirmMyEmailChange)
const tenantsMock = vi.mocked(tenants)

describe('EmailConfirmView', () => {
  afterEach(() => {
    confirmMyEmailChangeMock.mockReset()
    tenantsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('confirms the token and updates authenticated user state', async () => {
    confirmMyEmailChangeMock.mockResolvedValue({
      userId: 1,
      email: 'changed@example.test',
      displayName: 'Owner',
      serverRole: 'ServerOwner',
      passkeyCount: 0
    })
    tenantsMock.mockResolvedValue([])

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/email/confirm', component: EmailConfirmView }]
    })
    await router.push('/email/confirm#abc123')
    await router.isReady()

    const wrapper = mount(EmailConfirmView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(confirmMyEmailChangeMock).toHaveBeenCalledWith('abc123')
    expect(authState.user?.email).toBe('changed@example.test')
    expect(wrapper.text()).toContain('Email wurde bestaetigt: changed@example.test')
  })
})

function viewStubs() {
  return {
    Button: { template: '<button><slot />{{ label }}</button>', props: ['label'] },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Message: { template: '<div><slot /></div>' },
    ProgressSpinner: { template: '<div />' }
  }
}
