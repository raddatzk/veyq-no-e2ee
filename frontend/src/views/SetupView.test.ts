import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SetupView from './SetupView.vue'
import { setupAdmin, setupAdminWithPasskey } from '../api/setupClient'
import { createTenant, tenants } from '../api/tenantClient'
import { login } from '../api/authClient'
import { createTotpEnrollment, enableTotp, listMyPasskeys, updateMyPasskey } from '../features/settings/api'
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
  createTotpEnrollment: vi.fn(),
  enableTotp: vi.fn(),
  listMyPasskeys: vi.fn(),
  updateMyPasskey: vi.fn()
}))

vi.mock('qrcode', () => ({
  toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr-code')
}))

const setupAdminMock = vi.mocked(setupAdmin)
const setupAdminWithPasskeyMock = vi.mocked(setupAdminWithPasskey)
const createTenantMock = vi.mocked(createTenant)
const tenantsMock = vi.mocked(tenants)
const loginMock = vi.mocked(login)
const createTotpEnrollmentMock = vi.mocked(createTotpEnrollment)
const enableTotpMock = vi.mocked(enableTotp)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)

describe('SetupView', () => {
  afterEach(() => {
    setupAdminMock.mockReset()
    setupAdminWithPasskeyMock.mockReset()
    createTenantMock.mockReset()
    tenantsMock.mockReset()
    loginMock.mockReset()
    createTotpEnrollmentMock.mockReset()
    enableTotpMock.mockReset()
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

  it('asks for TOTP setup before privacy opt-ins after password admin setup', async () => {
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
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
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
    expect(inputs[0].attributes('name')).toBe('email')
    expect(inputs[0].attributes('autocomplete')).toBe('username')
    expect(inputs[1].attributes('name')).toBe('password')
    expect(inputs[1].attributes('autocomplete')).toBe('new-password')
    expect(inputs[1].attributes('passwordrules')).toBe('minlength: 10;')
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
    expect(createTotpEnrollmentMock).toHaveBeenCalledWith()
    expect(wrapper.text()).toContain('Authenticator-App einrichten')
    expect(wrapper.text()).not.toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Später einrichten').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:1:osm-tiles-enabled')).toBeNull()
    expect(wrapper.text()).toContain('Bereich erstellen')
  })

  it('enables TOTP before privacy opt-ins after password admin setup', async () => {
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
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    enableTotpMock.mockResolvedValue({
      userId: 1,
      email: 'admin@example.test',
      displayName: 'Administrator',
      permalink: 'administrator',
      serverRole: 'ServerOwner',
      passkeyCount: 0,
      hasPassword: true,
      totpEnabled: true
    })
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
    await inputs[2].setValue('Administrator')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.totp-qr-code img').attributes('src')).toBe('data:image/png;base64,qr-code')
    await wrapper.findAll('input').at(-1)!.setValue('123456')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(enableTotpMock).toHaveBeenCalledWith('welcome123456', 'JBSWY3DPEHPK3PXP', '123456')
    expect(authState.user?.totpEnabled).toBe(true)
    expect(wrapper.text()).toContain('Externe Inhalte')
  })

  it('generates the internal tenant name and requires owner email during setup', async () => {
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
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    createTenantMock.mockResolvedValue(tenantCreationResponse('family-trip'))
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
    const adminInputs = wrapper.findAll('input')
    await adminInputs[0].setValue('admin@example.test')
    await adminInputs[1].setValue('welcome123456')
    await adminInputs[2].setValue('Administrator')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await buttonByText(wrapper, 'Später einrichten').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('Permalink')
    const tenantActions = wrapper.findAll('.veyq-button-row').at(-1)!.findAll('button')
    expect(tenantActions.map((button) => button.text())).toEqual(['Überspringen', 'Bereich erstellen'])

    const tenantInputs = wrapper.findAll('input')
    await tenantInputs[0].setValue('Family Trip')
    await tenantInputs[1].setValue('family.example.test')

    const createButton = buttonByText(wrapper, 'Bereich erstellen')
    expect(createButton.attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(createTenantMock).not.toHaveBeenCalled()

    await tenantInputs[2].setValue('owner@example.test')
    expect(createButton.attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createTenantMock).toHaveBeenCalledWith(
      'family-trip',
      'Family Trip',
      'family.example.test',
      'owner@example.test'
    )
  })

  it('redirects home after tenant creation even when tenant refresh fails', async () => {
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
    tenantsMock
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error('tenant refresh failed'))
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    createTenantMock.mockResolvedValue(tenantCreationResponse('family-trip'))
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
    const adminInputs = wrapper.findAll('input')
    await adminInputs[0].setValue('admin@example.test')
    await adminInputs[1].setValue('welcome123456')
    await adminInputs[2].setValue('Administrator')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await buttonByText(wrapper, 'Später einrichten').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    const tenantInputs = wrapper.findAll('input')
    await tenantInputs[0].setValue('Family Trip')
    await tenantInputs[1].setValue('family.example.test')
    await tenantInputs[2].setValue('owner@example.test')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createTenantMock).toHaveBeenCalledWith(
      'family-trip',
      'Family Trip',
      'family.example.test',
      'owner@example.test'
    )
    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(wrapper.text()).not.toContain('Bereich konnte nicht erstellt werden')
  })

  it('shows loading and blocks duplicate tenant creation during setup', async () => {
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
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    let resolveCreateTenant: (value: ReturnType<typeof tenantCreationResponse>) => void = () => undefined
    createTenantMock.mockReturnValue(new Promise((resolve) => {
      resolveCreateTenant = resolve
    }))
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
    const adminInputs = wrapper.findAll('input')
    await adminInputs[0].setValue('admin@example.test')
    await adminInputs[1].setValue('welcome123456')
    await adminInputs[2].setValue('Administrator')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await buttonByText(wrapper, 'Später einrichten').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    const tenantInputs = wrapper.findAll('input')
    await tenantInputs[0].setValue('Family Trip')
    await tenantInputs[1].setValue('family.example.test')
    await tenantInputs[2].setValue('owner@example.test')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(buttonByText(wrapper, 'Überspringen').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, 'Bereich erstellen').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, 'Bereich erstellen').attributes('data-loading')).toBe('true')

    await wrapper.find('form').trigger('submit')
    expect(createTenantMock).toHaveBeenCalledTimes(1)

    resolveCreateTenant(tenantCreationResponse('family-trip'))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
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

function totpEnrollment() {
  return {
    secret: 'JBSWY3DPEHPK3PXP',
    provisioningUri: 'otpauth://totp/veyq:admin%40example.test?secret=JBSWY3DPEHPK3PXP',
    issuer: 'veyq',
    accountName: 'admin@example.test',
    digits: 6,
    periodSeconds: 30
  }
}

function tenantCreationResponse(name: string) {
  return {
    id: 1,
    name,
    displayName: 'Family Trip',
    domain: 'family.example.test',
    status: 'Active' as const,
    invitedOwnerEmail: 'owner@example.test'
  }
}

function viewStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :disabled="disabled" :data-loading="String(loading)" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type', 'disabled', 'loading']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    InputText: {
      template:
        '<input :value="modelValue" v-bind="$attrs" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue'],
      inheritAttrs: false
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input :value="modelValue" v-bind="inputProps" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue', 'inputProps']
    }
  }
}
