import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TenantJoinView from './TenantJoinView.vue'
import {
  getTenantRegistrationInvite,
  joinTenantRegistrationInvite,
  joinTenantRegistrationInviteWithPasskey
} from '../api/tenantRegistrationInviteClient'
import { bootstrap } from '../api/entrypointClient'
import { authState } from '../authState'
import { i18n } from '../i18n'
import { useThemeState } from '../shared/state/useThemeState'
import { listMyPasskeys, updateMyPasskey } from '../features/settings/api'

vi.mock('../api/tenantRegistrationInviteClient', () => ({
  getTenantRegistrationInvite: vi.fn(),
  joinTenantRegistrationInvite: vi.fn(),
  joinTenantRegistrationInviteWithPasskey: vi.fn()
}))

vi.mock('../api/entrypointClient', () => ({
  bootstrap: vi.fn()
}))

vi.mock('../features/settings/api', () => ({
  listMyPasskeys: vi.fn(),
  updateMyPasskey: vi.fn()
}))

const getTenantRegistrationInviteMock = vi.mocked(getTenantRegistrationInvite)
const joinTenantRegistrationInviteMock = vi.mocked(joinTenantRegistrationInvite)
const joinTenantRegistrationInviteWithPasskeyMock = vi.mocked(joinTenantRegistrationInviteWithPasskey)
const bootstrapMock = vi.mocked(bootstrap)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)

describe('TenantJoinView', () => {
  afterEach(() => {
    getTenantRegistrationInviteMock.mockReset()
    joinTenantRegistrationInviteMock.mockReset()
    joinTenantRegistrationInviteWithPasskeyMock.mockReset()
    bootstrapMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyPasskeyMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    useThemeState().setThemePreference('system')
    window.localStorage.clear()
  })

  it('renders the translated email label on tenant registration links', async () => {
    getTenantRegistrationInviteMock.mockResolvedValue({
      tenantId: 10,
      tenantName: 'friends',
      tenantDisplayName: 'Friends',
      tenantDomain: 'friends.example.test',
      expiresAt: '2026-06-15T12:00:00Z',
      requiresLogin: false
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/join/:token', component: TenantJoinView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/join/token-123')
    await router.isReady()

    const wrapper = mount(TenantJoinView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('E-Mail')
    expect(wrapper.text()).not.toContain('auth.email')
    expect(getTenantRegistrationInviteMock).toHaveBeenCalledWith('token-123')
  })

  it('lets new users join with a passkey by default', async () => {
    getTenantRegistrationInviteMock.mockResolvedValue({
      tenantId: 10,
      tenantName: 'friends',
      tenantDisplayName: 'Friends',
      tenantDomain: 'friends.example.test',
      expiresAt: '2026-06-15T12:00:00Z',
      requiresLogin: false
    })
    joinTenantRegistrationInviteWithPasskeyMock.mockResolvedValue({
      userId: 42,
      email: 'join@example.test',
      displayName: 'Join',
      permalink: 'join',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: false
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'join@example.test',
        displayName: 'Join',
        permalink: 'join',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: false
      },
      tenants: []
    })
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(9, '1Password')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'Private 1Password'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/join/:token', component: TenantJoinView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/join/token-123')
    await router.isReady()

    const wrapper = mount(TenantJoinView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    const submitButton = wrapper.find('button[type="submit"]')

    expect(wrapper.text()).toContain('Passkey')
    expect(wrapper.text()).not.toContain('Passwort (optional)')
    expect(wrapper.find('input[autocomplete="new-password"]').exists()).toBe(false)
    expect(wrapper.text().indexOf('Anzeigename')).toBeLessThan(wrapper.text().indexOf('Passkey'))
    expect(submitButton.attributes('disabled')).toBeDefined()

    await inputs[0].setValue('join@example.test')
    expect(submitButton.attributes('disabled')).toBeDefined()

    await inputs[1].setValue('Join')
    expect(submitButton.attributes('disabled')).toBeUndefined()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(joinTenantRegistrationInviteWithPasskeyMock).toHaveBeenCalledWith('token-123', 'join@example.test', 'Join')
    expect(joinTenantRegistrationInviteMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Passkey benennen')
    expect(router.currentRoute.value.path).toBe('/join/token-123')

    await wrapper.findAll('input').at(-1)!.setValue('  Private 1Password  ')
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(9, 'Private 1Password')
    expect(wrapper.text()).toContain('Externe Inhalte')

    const consentOptions = wrapper.findAll('input[type="checkbox"]')
    await consentOptions[0].setValue(true)
    await wrapper.findAll('button').find((button) => button.text() === 'Speichern')!.trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('veyq:user:42:osm-tiles-enabled')).toBe('true')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('exposes language and theme controls on tenant registration links', async () => {
    getTenantRegistrationInviteMock.mockResolvedValue({
      tenantId: 10,
      tenantName: 'friends',
      tenantDisplayName: 'Friends',
      tenantDomain: 'friends.example.test',
      expiresAt: '2026-06-15T12:00:00Z',
      requiresLogin: false
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/join/:token', component: TenantJoinView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/join/token-123')
    await router.isReady()

    const wrapper = mount(TenantJoinView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.find('button[aria-label="Sprache"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="Darstellung"]').exists()).toBe(true)
    const [languageMenu, themeMenu] = wrapper.findAllComponents({ name: 'TieredMenu' })
    const languageItems = languageMenu.props('model') as Array<{ label: string; command: () => void }>
    const themeItems = themeMenu.props('model') as Array<{ label: string; command: () => void }>

    expect(languageItems.map((item) => item.label)).toEqual(['Deutsch', 'Englisch'])
    expect(themeItems.map((item) => item.label)).toEqual(['System', 'Hell', 'Dunkel'])

    languageItems.find((item) => item.label === 'Englisch')!.command()
    themeItems.find((item) => item.label === 'Dunkel')!.command()

    expect(i18n.global.locale.value).toBe('en')
    expect(useThemeState().themePreference.value).toBe('dark')
    expect(window.localStorage.getItem('veyq:locale')).toBe('en')
    expect(window.localStorage.getItem('veyq:theme')).toBe('dark')
  })
})

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" :disabled="disabled">{{ label }}</button>',
      props: ['disabled', 'label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    InputText: {
      template:
        '<input :value="modelValue" v-bind="$attrs" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue'],
      inheritAttrs: false
    },
    Message: { template: '<div><slot /></div>' },
    PasskeyNameDialog: {
      name: 'PasskeyNameDialog',
      template:
        '<section v-if="visible">Passkey benennen<input :value="passkey?.displayName || \'\'" @input="value = $event.target.value" /><button type="button" @click="$emit(\'save\', value.trim())">Speichern</button><button type="button" @click="$emit(\'skip\')">Überspringen</button></section>',
      props: ['visible', 'passkey', 'saving'],
      data: () => ({ value: '' })
    },
    Password: {
      template:
        '<input :value="modelValue" v-bind="$attrs" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue'],
      inheritAttrs: false
    },
    RegistrationPrivacyOptIns: {
      name: 'RegistrationPrivacyOptIns',
      template:
        '<section>Externe Inhalte<input type="checkbox" @input="checked = $event.target.checked" /><button type="button" @click="save">Speichern</button></section>',
      data: () => ({ checked: false }),
      methods: {
        save() {
          if (this.checked) {
            window.localStorage.setItem('veyq:user:42:osm-tiles-enabled', 'true')
          }
          this.$emit('complete')
        }
      }
    },
    TieredMenu: {
      name: 'TieredMenu',
      template: '<div />',
      props: ['model']
    }
  }
}

function passkey(passkeyId: number, displayName: string) {
  return {
    passkeyId,
    displayName,
    aaguid: '00000000-0000-0000-0000-000000000000',
    relyingPartyId: 'friends.example.test',
    createdAt: '2026-06-01T12:00:00Z'
  }
}
