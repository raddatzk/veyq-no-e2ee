import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import InviteView from './InviteView.vue'
import { acceptInvitation, acceptInvitationWithPasskey, invitationMetadata } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import { createTotpEnrollment, enableTotp, listMyPasskeys, updateMyPasskey } from '../features/settings/api'
import { authState } from '../authState'

vi.mock('../api/invitationClient', () => ({
  acceptInvitation: vi.fn(),
  acceptInvitationWithPasskey: vi.fn(),
  invitationMetadata: vi.fn()
}))

vi.mock('../api/entrypointClient', () => ({
  bootstrap: vi.fn()
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

const acceptInvitationMock = vi.mocked(acceptInvitation)
const acceptInvitationWithPasskeyMock = vi.mocked(acceptInvitationWithPasskey)
const invitationMetadataMock = vi.mocked(invitationMetadata)
const bootstrapMock = vi.mocked(bootstrap)
const createTotpEnrollmentMock = vi.mocked(createTotpEnrollment)
const enableTotpMock = vi.mocked(enableTotp)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)

describe('InviteView', () => {
  afterEach(() => {
    acceptInvitationMock.mockReset()
    acceptInvitationWithPasskeyMock.mockReset()
    invitationMetadataMock.mockReset()
    bootstrapMock.mockReset()
    createTotpEnrollmentMock.mockReset()
    enableTotpMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyPasskeyMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    window.localStorage.clear()
  })

  it('asks for TOTP setup before privacy opt-ins after password invitation acceptance', async () => {
    invitationMetadataMock.mockResolvedValue({ email: 'invitee@example.test' })
    acceptInvitationMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        permalink: 'invitee',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      tenants: [tenant('friends')]
    })
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(9, '1Password')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'Private 1Password'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Passwort')!.trigger('click')
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).toBe('email')
    expect(inputs[0].attributes('autocomplete')).toBe('username')
    expect((inputs[0].element as HTMLInputElement).value).toBe('invitee@example.test')
    expect(inputs[2].attributes('name')).toBe('password')
    expect(inputs[2].attributes('autocomplete')).toBe('new-password')
    expect(inputs[2].attributes('passwordrules')).toBe('minlength: 10;')
    await inputs[1].setValue('Invitee')
    await inputs[2].setValue('welcome123456')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationMock).toHaveBeenCalledWith(42, 'invite-token', 'welcome123456', 'Invitee')
    expect(createTotpEnrollmentMock).toHaveBeenCalledWith()
    expect(wrapper.text()).toContain('Authenticator-App einrichten')
    expect(wrapper.text()).not.toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Später einrichten').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Externe Inhalte')

    await buttonByText(wrapper, 'Keine akzeptieren').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:42:osm-tiles-enabled')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('enables TOTP before privacy opt-ins after password invitation acceptance', async () => {
    invitationMetadataMock.mockResolvedValue({ email: 'invitee@example.test' })
    acceptInvitationMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        permalink: 'invitee',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      tenants: [tenant('friends')]
    })
    createTotpEnrollmentMock.mockResolvedValue(totpEnrollment())
    enableTotpMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true,
      totpEnabled: true
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Passwort')!.trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('Invitee')
    await inputs[2].setValue('welcome123456')
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

  it('asks for privacy opt-ins after passkey invitation acceptance and passkey naming', async () => {
    acceptInvitationWithPasskeyMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 1,
      hasPassword: false
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        permalink: 'invitee',
        serverRole: 'ServerMember',
        passkeyCount: 1,
        hasPassword: false
      },
      tenants: [tenant('friends')]
    })
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(9, '1Password')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'Private 1Password'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input').setValue('Invitee')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationWithPasskeyMock).toHaveBeenCalledWith(
      42,
      'invite-token',
      'Invitee'
    )
    expect(acceptInvitationMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Passkey benennen')
    expect(window.localStorage.getItem('veyq:user:42:osm-tiles-enabled')).toBeNull()

    await wrapper.findAll('input').at(-1)!.setValue('  Private 1Password  ')
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(9, 'Private 1Password')
    expect(wrapper.text()).toContain('Externe Inhalte')

    const consentOptions = wrapper.findAll('input[type="checkbox"]')
    await consentOptions[0].setValue(true)
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:42:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('accepts an invitation for an already signed-in user without privacy opt-ins', async () => {
    authState.isAuthenticated = true
    authState.user = {
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true
    }
    acceptInvitationMock.mockResolvedValue(authState.user)
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: authState.user,
      tenants: [tenant('friends')]
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationMock).toHaveBeenCalledWith(42, 'invite-token')
    expect(wrapper.text()).not.toContain('Externe Inhalte')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('requires a display name before starting passkey invitation acceptance', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationWithPasskeyMock).not.toHaveBeenCalled()
    expect(acceptInvitationMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Bitte gib einen Anzeigenamen ein.')
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
    provisioningUri: 'otpauth://totp/veyq:invitee%40example.test?secret=JBSWY3DPEHPK3PXP',
    issuer: 'veyq',
    accountName: 'invitee@example.test',
    digits: 6,
    periodSeconds: 30
  }
}

function tenant(name: string) {
  return {
    id: name === 'friends' ? 1 : 2,
    name,
    displayName: name,
    domain: `${name}.example.test`,
    role: 'TenantMember' as const,
    admins: []
  }
}

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['disabled', 'label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    Message: { template: '<div><slot /></div>' },
    InputText: {
      template:
        '<input :value="modelValue" v-bind="$attrs" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue'],
      inheritAttrs: false
    },
    Password: {
      template:
        '<input :value="modelValue" v-bind="inputProps" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue', 'inputProps']
    }
  }
}
