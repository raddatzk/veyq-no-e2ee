import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import ResetPasskeyView from './ResetPasskeyView.vue'
import { resetPasskey } from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { authState } from '../authState'

vi.mock('../api/authClient', () => ({
  resetPasskey: vi.fn()
}))

vi.mock('../api/tenantClient', () => ({
  tenants: vi.fn()
}))

const resetPasskeyMock = vi.mocked(resetPasskey)
const tenantsMock = vi.mocked(tenants)

describe('ResetPasskeyView', () => {
  afterEach(() => {
    resetPasskeyMock.mockReset()
    tenantsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('resets the passkey with the reset token and authenticates the user', async () => {
    resetPasskeyMock.mockResolvedValue({
      userId: 42,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 1,
      hasPassword: false
    })
    tenantsMock.mockResolvedValue([])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/passkey/reset/:userId', component: ResetPasskeyView }
      ]
    })
    await router.push('/passkey/reset/42#reset-token')
    await router.isReady()

    const wrapper = mount(ResetPasskeyView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(resetPasskeyMock).toHaveBeenCalledWith(42, 'reset-token')
    expect(authState.isAuthenticated).toBe(true)
    expect(router.currentRoute.value.fullPath).toBe('/')
  })
})

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Message: { template: '<div><slot /></div>' }
  }
}
