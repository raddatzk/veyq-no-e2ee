import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SetupView from './SetupView.vue'
import { setupAdmin, setupAdminWithPasskey } from '../api/setupClient'
import { tenants } from '../api/tenantClient'
import { authState } from '../authState'
import { serverState } from '../serverState'

vi.mock('../api/setupClient', () => ({
  setupAdmin: vi.fn(),
  setupAdminWithPasskey: vi.fn()
}))

vi.mock('../api/tenantClient', () => ({
  createTenant: vi.fn(),
  tenants: vi.fn()
}))

vi.mock('../api/authClient', () => ({
  login: vi.fn()
}))

const setupAdminMock = vi.mocked(setupAdmin)
const setupAdminWithPasskeyMock = vi.mocked(setupAdminWithPasskey)
const tenantsMock = vi.mocked(tenants)

describe('SetupView', () => {
  afterEach(() => {
    setupAdminMock.mockReset()
    setupAdminWithPasskeyMock.mockReset()
    tenantsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    serverState.setupCompleted = false
  })

  it('requires a display name before starting passkey setup', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/setup', component: SetupView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/setup')
    await router.isReady()

    const wrapper = mount(SetupView, { global: { plugins: [router], stubs: viewStubs() } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin@example.test')
    await inputs[1].setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(setupAdminWithPasskeyMock).not.toHaveBeenCalled()
    expect(setupAdminMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Bitte gib einen Anzeigenamen ein.')
  })

  it('requires a passkey name before starting passkey setup', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/setup', component: SetupView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/setup')
    await router.isReady()

    const wrapper = mount(SetupView, { global: { plugins: [router], stubs: viewStubs() } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin@example.test')
    await inputs[1].setValue('Administrator')
    await inputs[2].setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(setupAdminWithPasskeyMock).not.toHaveBeenCalled()
    expect(setupAdminMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Bitte gib einen Passkey-Namen ein.')
  })

  it('creates a passkey admin with trimmed user and passkey names', async () => {
    setupAdminWithPasskeyMock.mockResolvedValue({
      userId: 1,
      email: 'admin@example.test',
      displayName: 'Administrator',
      permalink: 'administrator',
      serverRole: 'ServerOwner',
      passkeyCount: 1
    })
    tenantsMock.mockResolvedValue([])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/setup', component: SetupView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/setup')
    await router.isReady()

    const wrapper = mount(SetupView, { global: { plugins: [router], stubs: viewStubs() } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin@example.test')
    await inputs[1].setValue('  Administrator  ')
    await inputs[2].setValue('  MacBook Touch ID  ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(setupAdminWithPasskeyMock).toHaveBeenCalledWith(
      'admin@example.test',
      'Administrator',
      'MacBook Touch ID'
    )
    expect(setupAdminMock).not.toHaveBeenCalled()
  })
})

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    InputText: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
