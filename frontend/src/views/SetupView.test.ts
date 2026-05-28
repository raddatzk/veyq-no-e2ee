import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SetupView from './SetupView.vue'
import { setupAdmin, setupAdminWithPasskey } from '../api/setupClient'
import { tenants } from '../api/tenantClient'
import { login } from '../api/authClient'
import { listMyPasskeys, updateMyPasskey } from '../features/settings/api'
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

vi.mock('../features/settings/api', () => ({
  listMyPasskeys: vi.fn(),
  updateMyPasskey: vi.fn()
}))

const setupAdminMock = vi.mocked(setupAdmin)
const setupAdminWithPasskeyMock = vi.mocked(setupAdminWithPasskey)
const tenantsMock = vi.mocked(tenants)
const loginMock = vi.mocked(login)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)

describe('SetupView', () => {
  afterEach(() => {
    setupAdminMock.mockReset()
    setupAdminWithPasskeyMock.mockReset()
    tenantsMock.mockReset()
    loginMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyPasskeyMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    serverState.setupCompleted = false
    window.localStorage.clear()
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
    expect(wrapper.text()).not.toContain('Externe Inhalte')
  })

  it('asks for privacy opt-ins after passkey admin setup and passkey naming', async () => {
    setupAdminWithPasskeyMock.mockResolvedValue({
      userId: 1,
      email: 'admin@example.test',
      displayName: 'Administrator',
      permalink: 'administrator',
      serverRole: 'ServerOwner',
      passkeyCount: 1,
      hasPassword: false
    })
    tenantsMock.mockResolvedValue([])
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(5, 'Apple Passwords')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(5, 'MacBook Touch ID'))
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
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(setupAdminWithPasskeyMock).toHaveBeenCalledWith('admin@example.test', 'Administrator')
    expect(setupAdminMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Passkey benennen')
    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBeNull()

    await wrapper.findAll('input').at(-1)!.setValue('  MacBook Touch ID  ')
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(5, 'MacBook Touch ID')
    expect(wrapper.text()).toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Alle akzeptieren').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()
    expect(wrapper.text()).toContain('Bereich erstellen')
  })

  it('asks for privacy opt-ins after password admin setup', async () => {
    setupAdminMock.mockResolvedValue(undefined)
    loginMock.mockResolvedValue({
      userId: 1,
      email: 'admin@example.test',
      displayName: 'Administrator',
      permalink: 'administrator',
      serverRole: 'ServerOwner',
      passkeyCount: 0,
      hasPassword: true
    })
    tenantsMock.mockResolvedValue([])
    window.localStorage.setItem('veyq:user:1:osm-tiles-enabled', 'true')
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
    await buttonByText(wrapper, 'Passwort').trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin@example.test')
    await inputs[1].setValue('welcome123456')
    await inputs[2].setValue('  Administrator  ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(setupAdminMock).toHaveBeenCalledWith({
      password: 'welcome123456',
      displayName: 'Administrator',
      email: 'admin@example.test'
    })
    expect(loginMock).toHaveBeenCalledWith('admin@example.test', 'welcome123456')
    expect(wrapper.text()).toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBeNull()
    expect(wrapper.text()).toContain('Bereich erstellen')
  })
})

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function passkey(passkeyId: number, displayName: string) {
  return {
    passkeyId,
    displayName,
    createdAt: '2026-05-25T10:00:00Z',
    lastUsedAt: undefined
  }
}

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
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
