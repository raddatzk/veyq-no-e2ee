import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteTenant, tenants } from '../../../api/tenantClient'
import {
  createTenantRegistrationInvite,
  listTenantRegistrationInvites,
  revokeTenantRegistrationInvite
} from '../../../api/tenantRegistrationInviteClient'
import { authState } from '../../../authState'
import {
  deleteTenantLogo,
  getTenantSettings,
  getTenantUsers,
  inviteTenantUser,
  reinviteTenantUser,
  removeTenantUser,
  updateTenantLogo,
  updateTenantSettings,
  updateTenantUserRole
} from '../api'
import TenantSettingsPanel from './TenantSettingsPanel.vue'

vi.mock('../../../api/tenantClient', () => ({
  deleteTenant: vi.fn(),
  tenants: vi.fn()
}))

vi.mock('../../../api/tenantRegistrationInviteClient', () => ({
  createTenantRegistrationInvite: vi.fn(),
  listTenantRegistrationInvites: vi.fn(),
  revokeTenantRegistrationInvite: vi.fn()
}))

const pushMock = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('../api', () => ({
  getTenantUsers: vi.fn(),
  getTenantSettings: vi.fn(),
  deleteTenantLogo: vi.fn(),
  inviteTenantUser: vi.fn(),
  reinviteTenantUser: vi.fn(),
  removeTenantUser: vi.fn(),
  updateTenantLogo: vi.fn(),
  updateTenantSettings: vi.fn(),
  updateTenantUserRole: vi.fn()
}))

const deleteTenantLogoMock = vi.mocked(deleteTenantLogo)
const getTenantUsersMock = vi.mocked(getTenantUsers)
const getTenantSettingsMock = vi.mocked(getTenantSettings)
const inviteTenantUserMock = vi.mocked(inviteTenantUser)
const reinviteTenantUserMock = vi.mocked(reinviteTenantUser)
const removeTenantUserMock = vi.mocked(removeTenantUser)
const updateTenantLogoMock = vi.mocked(updateTenantLogo)
const updateTenantSettingsMock = vi.mocked(updateTenantSettings)
const updateTenantUserRoleMock = vi.mocked(updateTenantUserRole)
const deleteTenantMock = vi.mocked(deleteTenant)
const tenantsMock = vi.mocked(tenants)
const createTenantRegistrationInviteMock = vi.mocked(createTenantRegistrationInvite)
const listTenantRegistrationInvitesMock = vi.mocked(listTenantRegistrationInvites)
const revokeTenantRegistrationInviteMock = vi.mocked(revokeTenantRegistrationInvite)

describe('TenantSettingsPanel', () => {
  beforeEach(() => {
    getTenantSettingsMock.mockResolvedValue(tenantSettings())
    getTenantUsersMock.mockResolvedValue([])
    listTenantRegistrationInvitesMock.mockResolvedValue([])
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn() }
    })
  })

  afterEach(() => {
    vi.resetAllMocks()
    pushMock.mockReset()
    authState.user = null
    authState.tenants = []
  })

  it('saves tenant terms from the multilingual editor', async () => {
    getTenantUsersMock.mockResolvedValue([])
    getTenantSettingsMock.mockResolvedValue(tenantSettings())
    updateTenantSettingsMock.mockResolvedValue(
      tenantSettings({ termsMarkdownByLocale: { de: '# Hinweise' }, termsVersion: 1 })
    )

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.tenant-settings-header h1').text()).toBe('Friends')
    expect(wrapper.findAll('.veyq-surface h1').at(0)!.text()).toBe('Logo')
    await wrapper.find('select').setValue('de')
    await wrapper.findAll('button').find((button) => button.text().includes('Sprache hinzufügen'))!.trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue('# Hinweise')
    await wrapper.findAll('button').find((button) => button.text() === 'Speichern')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateTenantSettingsMock).toHaveBeenCalledWith('friends', {
      displayName: 'Friends',
      termsMarkdownByLocale: { de: '# Hinweise' }
    })
    expect(wrapper.text()).toContain('Nutzungsbedingungen wurden gespeichert.')
  })

  it('shows existing terms languages as editable rows', async () => {
    getTenantUsersMock.mockResolvedValue([])
    getTenantSettingsMock.mockResolvedValue(tenantSettings({ termsMarkdownByLocale: { de: '# Alt' } }))

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('Deutsch')
    expect(wrapper.find('textarea').exists()).toBe(false)

    await wrapper.findAll('button').find((button) => button.text().includes('Bearbeiten'))!.trigger('click')
    await flushPromises()

    expect(wrapper.find('textarea').element.value).toBe('# Alt')
  })

  it('imports tenant owner email addresses into the contact template clause', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(1, 'Kevin', 'kevin@example.test', 'TenantOwner')
    ])
    getTenantSettingsMock.mockResolvedValue(tenantSettings())

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('Vorlage einfügen'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('select').at(-1)!.setValue('en')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Englisch')
    await wrapper.findAll('button').find((button) => button.text().includes('Bearbeiten'))!.trigger('click')
    await flushPromises()

    expect(wrapper.find('textarea').element.value).toContain('contact: kevin@example.test.')
    expect(wrapper.find('textarea').element.value).toContain('## Purpose of this instance')
    expect(wrapper.find('textarea').element.value).not.toContain('Tenant Owner: Kevin')
  })

  it('uploads a tenant logo and updates the tenant state', async () => {
    authState.tenants = [
      {
        id: 10,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.test',
        role: 'TenantOwner',
        admins: []
      }
    ]
    getTenantUsersMock.mockResolvedValue([])
    getTenantSettingsMock.mockResolvedValue(tenantSettings())
    const file = new File(['logo'], 'logo.png', { type: 'image/png' })
    updateTenantLogoMock.mockResolvedValue(tenantSettings({ logoUrl: '/api/tenants/friends/logo?v=1' }))

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()

    expect(updateTenantLogoMock).toHaveBeenCalledWith('friends', file)
    expect(authState.tenants[0].logoUrl).toBe('/api/tenants/friends/logo?v=1')
    expect(wrapper.text()).toContain('Logo wurde gespeichert.')
  })

  it('removes a tenant logo and clears the tenant state', async () => {
    authState.tenants = [
      {
        id: 10,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.test',
        role: 'TenantOwner',
        admins: [],
        logoUrl: '/api/tenants/friends/logo?v=1'
      }
    ]
    getTenantUsersMock.mockResolvedValue([])
    getTenantSettingsMock.mockResolvedValue(tenantSettings({ logoUrl: '/api/tenants/friends/logo?v=1' }))
    deleteTenantLogoMock.mockResolvedValue(tenantSettings({ logoUrl: undefined }))

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('button[aria-label="Logo entfernen"]').trigger('click')
    await flushPromises()

    expect(deleteTenantLogoMock).toHaveBeenCalledWith('friends')
    expect(authState.tenants[0].logoUrl).toBeUndefined()
    expect(wrapper.text()).toContain('Logo wurde gelöscht.')
  })

  it('requires a custom contact when no tenant owner email is available', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(1, 'Kevin', '', 'TenantOwner')
    ])
    getTenantSettingsMock.mockResolvedValue(tenantSettings())

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('Vorlage einfügen'))!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('button').filter((button) => button.text() === 'Vorlage einfügen').at(-1)!.attributes('disabled')).toBeDefined()
  })

  it('invites a tenant user directly by email and role', async () => {
    getTenantUsersMock.mockResolvedValue([])
    inviteTenantUserMock.mockResolvedValue(tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantOwner'))

    const wrapper = mountPanel()
    await flushPromises()

    expect(getTenantUsersMock).toHaveBeenCalledWith('friends')
    await wrapper.findAll('button').find((button) => button.text().includes('User einladen'))!.trigger('click')
    await wrapper.find('input[type="email"]').setValue('invitee@example.test')
    await wrapper.findAll('select').at(-1)!.setValue('TenantOwner')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    expect(inviteTenantUserMock).toHaveBeenCalledWith('friends', 'invitee@example.test', 'TenantOwner')
    expect(wrapper.text()).toContain('Einladung wurde gesendet.')
    expect(wrapper.text()).toContain('invitee@example.test')
  })

  it('creates and copies a tenant registration link', async () => {
    createTenantRegistrationInviteMock.mockResolvedValue({
      invite: registrationInvite(12),
      joinUrl: 'https://friends.test/join/token-123',
      token: 'token-123'
    })

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('Registrierungslink erstellen'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-2)!.setValue('30')
    await wrapper.findAll('input').at(-1)!.setValue('100')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()

    expect(createTenantRegistrationInviteMock).toHaveBeenCalledWith('friends', 30, 100)
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://friends.test/join/token-123')
    expect(wrapper.text()).toContain('Registrierungslink wurde erstellt und kopiert.')
    expect(wrapper.text()).toContain('Registrierungslink #12')
  })

  it('lists and revokes tenant registration links', async () => {
    listTenantRegistrationInvitesMock.mockResolvedValue([registrationInvite(12)])
    revokeTenantRegistrationInviteMock.mockResolvedValue(registrationInvite(12, 'Revoked'))

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('Registrierungslink #12')
    await wrapper.find('button[aria-label="Link kopieren"]').trigger('click')
    await flushPromises()

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://friends.test/join/invite-12')
    expect(wrapper.text()).toContain('Registrierungslink wurde kopiert.')
    await wrapper.find('button[aria-label="Link widerrufen"]').trigger('click')
    await flushPromises()

    expect(revokeTenantRegistrationInviteMock).toHaveBeenCalledWith('friends', 12)
    expect(wrapper.text()).toContain('Widerrufen')
    expect(wrapper.text()).toContain('Registrierungslink wurde widerrufen.')
  })

  it('reinvites pending users without changing tenant role', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending')
    ])
    reinviteTenantUserMock.mockResolvedValue(
      tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending')
    )

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('button[aria-label="Erneut einladen"]').trigger('click')
    await flushPromises()

    expect(reinviteTenantUserMock).toHaveBeenCalledWith('friends', 2)
    expect(wrapper.text()).toContain('Einladung wurde erneut gesendet.')
  })

  it('shows loading only on the pending user resend action', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending')
    ])
    let resolveReinvite!: (value: ReturnType<typeof tenantUser>) => void
    reinviteTenantUserMock.mockReturnValue(new Promise((resolve) => {
      resolveReinvite = resolve
    }))

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('button[aria-label="Erneut einladen"]').trigger('click')

    expect(wrapper.find('button[aria-label="Erneut einladen"]').attributes('data-loading')).toBe('true')
    expect(wrapper.find('button[aria-label="Mitglied entfernen"]').attributes('data-loading')).toBeUndefined()

    resolveReinvite(tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending'))
    await flushPromises()

    expect(wrapper.find('button[aria-label="Erneut einladen"]').attributes('data-loading')).toBeUndefined()
  })

  it('updates tenant member roles from the tenant member table', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Member', 'member@example.test', 'TenantMember')
    ])
    updateTenantUserRoleMock.mockResolvedValue(
      tenantUser(2, 'Member', 'member@example.test', 'TenantOwner')
    )

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.findAll('select').at(0)!.setValue('TenantOwner')
    await flushPromises()

    expect(updateTenantUserRoleMock).toHaveBeenCalledWith('friends', 2, 'TenantOwner')
    expect(wrapper.text()).toContain('Rolle wurde gespeichert.')
  })

  it('soft removes active tenant members', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Member', 'member@example.test', 'TenantMember')
    ])
    removeTenantUserMock.mockResolvedValue()

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('button[aria-label="Mitglied entfernen"]').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(removeTenantUserMock).toHaveBeenCalledWith('friends', 2)
    expect(wrapper.text()).toContain('Entfernt')
    expect(wrapper.text()).toContain('Mitglied wurde entfernt.')
  })

  it('requires the tenant display name before deleting a tenant', async () => {
    const owner = {
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerMember' as const,
      passkeyCount: 0,
      hasPassword: true
    }
    authState.user = owner
    authState.tenants = [
      {
        id: 10,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.test',
        role: 'TenantOwner',
        admins: [owner]
      }
    ]
    getTenantUsersMock.mockResolvedValue([
      tenantUser(1, 'Owner', 'owner@example.test', 'TenantOwner')
    ])
    deleteTenantMock.mockResolvedValue()
    tenantsMock.mockResolvedValue([])

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Löschen')!
      .trigger('click')
    const deleteButton = () =>
      wrapper.findAll('button').filter((button) => button.text() === 'Löschen').at(-1)!
    expect(deleteButton().attributes('disabled')).toBeDefined()

    await wrapper.findAll('input').at(-1)!.setValue('Friends')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteTenantMock).toHaveBeenCalledWith('friends')
    expect(tenantsMock).toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/')
  })

  it('renders tenant setting blocks in the requested order', async () => {
    const owner = {
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerMember' as const,
      passkeyCount: 0,
      hasPassword: true
    }
    authState.user = owner
    getTenantUsersMock.mockResolvedValue([
      tenantUser(1, 'Owner', 'owner@example.test', 'TenantOwner')
    ])

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.tenant-settings-header h1').text()).toBe('Friends')
    const headings = wrapper.findAll('.veyq-surface h1').map((heading) => heading.text())
    expect(headings).toEqual([
      'Logo',
      'Mitglieder',
      'Registrierungslinks',
      'Gruppen',
      'Nutzungsbedingungen für Shares',
      'Gefährliche Zone'
    ])
  })
})

function mountPanel() {
  return mount(TenantSettingsPanel, {
    props: {
      tenantName: 'friends'
    },
    global: {
      stubs: {
        Button: {
          template: '<button v-bind="$attrs" :type="type || \'button\'" :disabled="disabled" :data-loading="loading ? \'true\' : undefined" @click="$emit(\'click\', $event)"><slot />{{ label }}</button>',
          props: ['disabled', 'label', 'loading', 'type']
        },
        Dialog: {
          template: '<div v-if="visible"><slot /></div>',
          props: ['visible']
        },
        InputText: {
          template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          props: ['disabled', 'modelValue']
        },
        FileUpload: {
          name: 'FileUpload',
          template: '<button type="button">{{ chooseLabel }}</button>',
          props: ['chooseLabel']
        },
        GroupSettingsPanel: {
          template: '<section class="veyq-surface settings-panel"><h1>Gruppen</h1></section>'
        },
        Message: { template: '<div><slot /></div>' },
        ProgressSpinner: { template: '<div />' },
        Select: {
          props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
          emits: ['update:modelValue'],
          template: '<select v-bind="$attrs" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option[optionValue]" :value="option[optionValue]">{{ option[optionLabel] }}</option></select>'
        }
      }
    }
  })
}

function tenantSettings(overrides = {}) {
  return {
    tenantId: 10,
    name: 'friends',
    displayName: 'Friends',
    termsVersion: 0,
    termsMarkdownByLocale: undefined,
    ...overrides
  }
}

function tenantUser(
  userId: number,
  displayName: string,
  email: string,
  role: 'TenantOwner' | 'TenantMember',
  status: 'Active' | 'Pending' = 'Active'
) {
  return {
    user: {
      userId,
      displayName,
      permalink: displayName.toLowerCase()
    },
    email,
    role,
    status
  } as const
}

function registrationInvite(
  id: number,
  status: 'Active' | 'Expired' | 'Revoked' | 'Exhausted' = 'Active'
) {
  return {
    id,
    tenantId: 10,
    tenantName: 'friends',
    createdByUserId: 1,
    joinUrl: `https://friends.test/join/invite-${id}`,
    expiresAt: '2026-06-15T12:00:00Z',
    revokedAt: status === 'Revoked' ? '2026-06-03T12:00:00Z' : undefined,
    maxRegistrations: 100,
    registrationCount: 2,
    createdAt: '2026-06-01T12:00:00Z',
    status
  } as const
}
