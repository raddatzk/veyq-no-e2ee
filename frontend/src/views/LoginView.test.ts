import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from './LoginView.vue'
import {
  completePasskeyLink,
  createPasskeyLink,
  forgotPasskey,
  forgotPassword,
  login,
  loginWithPasskey,
  passkeyLoginDomains
} from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { authState } from '../authState'
import { setLocale } from '../i18n'

vi.mock('../api/authClient', () => ({
  completePasskeyLink: vi.fn(),
  createPasskeyLink: vi.fn(),
  forgotPasskey: vi.fn(),
  forgotPassword: vi.fn(),
  login: vi.fn(),
  loginWithPasskey: vi.fn(),
  passkeyLoginDomains: vi.fn()
}))

vi.mock('../api/tenantClient', () => ({
  tenants: vi.fn()
}))

const completePasskeyLinkMock = vi.mocked(completePasskeyLink)
const createPasskeyLinkMock = vi.mocked(createPasskeyLink)
const forgotPasskeyMock = vi.mocked(forgotPasskey)
const forgotPasswordMock = vi.mocked(forgotPassword)
const loginMock = vi.mocked(login)
const loginWithPasskeyMock = vi.mocked(loginWithPasskey)
const passkeyLoginDomainsMock = vi.mocked(passkeyLoginDomains)
const tenantsMock = vi.mocked(tenants)

describe('LoginView', () => {
  afterEach(() => {
    completePasskeyLinkMock.mockReset()
    createPasskeyLinkMock.mockReset()
    forgotPasskeyMock.mockReset()
    forgotPasswordMock.mockReset()
    loginMock.mockReset()
    loginWithPasskeyMock.mockReset()
    passkeyLoginDomainsMock.mockReset()
    tenantsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('requests password reset for the entered email', async () => {
    forgotPasswordMock.mockResolvedValue()
    passkeyLoginDomainsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.findAll('button').find((button) => button.text() === 'Passwort')!.trigger('click')
    await wrapper.find('input[type="email"]').setValue('owner@example.test')
    await wrapper.findAll('button').find((button) => button.text() === 'Passwort vergessen')!.trigger('click')
    await flushPromises()

    expect(forgotPasswordMock).toHaveBeenCalledWith('owner@example.test')
    expect(wrapper.text()).toContain('wurde eine E-Mail zum Zurücksetzen gesendet')
  })

  it('uses passkey as the initial login method', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([])
    loginWithPasskeyMock.mockResolvedValue({
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 1,
      hasPassword: true
    })
    tenantsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    expect(wrapper.findAll('button').some((button) => button.text() === 'Mit Passkey einloggen')).toBe(true)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(loginWithPasskeyMock).toHaveBeenCalledWith()
    expect(loginMock).not.toHaveBeenCalled()
  })

  it('requests passkey reset for the entered email', async () => {
    forgotPasskeyMock.mockResolvedValue()
    passkeyLoginDomainsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input[type="email"]').setValue('owner@example.test')
    await wrapper.findAll('button').find((button) => button.text() === 'Passkey zurücksetzen')!.trigger('click')
    await flushPromises()

    expect(forgotPasskeyMock).toHaveBeenCalledWith('owner@example.test')
    expect(wrapper.text()).toContain('wurde eine E-Mail zum Zurücksetzen gesendet')
  })

  it('renders passkey login options for other tenant domains', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([
      {
        domain: 'memories.raddatz.me',
        origin: 'https://memories.raddatz.me',
        current: false
      }
    ])
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Mit memories.raddatz.me einloggen')
  })

  it('shows passkey creation CTA when returning with a fragment link code and cleans the URL', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login?redirect=/memories#passkeyLinkCode=code-1')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Passkey fuer')
    expect(wrapper.text()).toContain('anlegen')
    expect(wrapper.text()).toContain('Passkeys sind aus Sicherheitsgruenden an eine Webseite gebunden')
    expect(router.currentRoute.value.hash).toBe('')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/memories' })
  })

  it('keeps accepting query link codes as a compatibility fallback', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login?passkeyLinkCode=code-1&redirect=/memories')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Passkey fuer')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/memories' })
  })

  it('completes passkey linking and follows the sanitized redirect', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([])
    completePasskeyLinkMock.mockResolvedValue({
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 1,
      hasPassword: true
    })
    tenantsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login?redirect=/memories#passkeyLinkCode=code-1')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('anlegen'))!.trigger('click')
    await flushPromises()

    expect(completePasskeyLinkMock).toHaveBeenCalledWith('code-1')
    expect(router.currentRoute.value.fullPath).toBe('/memories')
  })

  it('ignores external-looking redirect paths after passkey login', async () => {
    passkeyLoginDomainsMock.mockResolvedValue([])
    loginWithPasskeyMock.mockResolvedValue({
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 1,
      hasPassword: true
    })
    tenantsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login?redirect=//evil.example.test')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('renders authentication labels in English', async () => {
    setLocale('en')
    passkeyLoginDomainsMock.mockResolvedValue([])
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })

    expect(wrapper.text()).toContain('Log in with passkey')
  })
})

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/login', component: LoginView }]
  })
}

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    InputText: {
      template:
        '<input type="email" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
