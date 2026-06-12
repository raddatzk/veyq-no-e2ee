import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  disableManagedUser,
  enableManagedUser,
  getManagedUsers,
  getServerNotifications,
  getServerSettings,
  getServerUpdateEvents,
  updateManagedUserRole,
  type ServerSettingsResponse
} from '../api'
import ServerSettingsPanel from './ServerSettingsPanel.vue'

vi.mock('../api', () => ({
  getServerSettings: vi.fn(),
  getServerNotifications: vi.fn(),
  getServerUpdateEvents: vi.fn(),
  getManagedUsers: vi.fn(),
  updateManagedUserRole: vi.fn(),
  disableManagedUser: vi.fn(),
  enableManagedUser: vi.fn()
}))

const disableManagedUserMock = vi.mocked(disableManagedUser)
const enableManagedUserMock = vi.mocked(enableManagedUser)
const getManagedUsersMock = vi.mocked(getManagedUsers)
const getServerSettingsMock = vi.mocked(getServerSettings)
const getServerNotificationsMock = vi.mocked(getServerNotifications)
const getServerUpdateEventsMock = vi.mocked(getServerUpdateEvents)
const updateManagedUserRoleMock = vi.mocked(updateManagedUserRole)

describe('ServerSettingsPanel', () => {
  afterEach(() => {
    vi.resetAllMocks()
  })

  function mockServerPanel(settings = serverSettings()) {
    getServerSettingsMock.mockResolvedValue(settings)
    getServerNotificationsMock.mockResolvedValue([])
    getServerUpdateEventsMock.mockResolvedValue([])
    getManagedUsersMock.mockResolvedValue({ users: [] })
  }

  it('renders license and edition information', async () => {
    mockServerPanel()

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Lizenz & Edition')
    expect(wrapper.text()).toContain('Community Edition · AGPL-3.0-only')
    expect(wrapper.find('a[href="https://github.com/raddatzk/veyq"]').exists()).toBe(true)
    expect(wrapper.find('a[href*="/commit/"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('veyq unterstützen')
  })

  it('renders a GitHub commit link from the license revision', async () => {
    mockServerPanel(
      serverSettings({
        license: {
          ...serverSettings().license,
          revision: 'sha-abcdef'
        }
      })
    )

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    const commitLink = wrapper.find('a[href="https://github.com/raddatzk/veyq/commit/abcdef"]')
    expect(commitLink.exists()).toBe(true)
    expect(commitLink.text()).toBe('Commit')
  })

  it('renders support action from server settings', async () => {
    mockServerPanel(serverSettings({ support: { url: 'https://support.example.test/veyq' } }))

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('veyq unterstützen')
    expect(wrapper.find('a[href="https://support.example.test/veyq"]').text()).toContain('Unterstützen')
  })

  it('renders translated mobile handoff capability', async () => {
    mockServerPanel(
      serverSettings({
        capabilities: [{ id: 'auth.mobile-handoff', introducedInVersion: '1.0.0', audience: 'ALL_USERS' }]
      })
    )

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Mobiler Login-Handoff')
    expect(wrapper.text()).toContain('Native mobile Apps über den Browser-Login mit einer Server-Session verbinden.')
    expect(wrapper.text()).not.toContain('auth.mobile-handoff')
  })

  it('disables and enables managed users from the server user list', async () => {
    mockServerPanel()
    getManagedUsersMock.mockResolvedValue({
      users: [managedUser(2, 'Member', 'member@example.test', 'Active')]
    })
    disableManagedUserMock.mockResolvedValue(managedUser(2, 'Member', 'member@example.test', 'Disabled'))
    enableManagedUserMock.mockResolvedValue(managedUser(2, 'Member', 'member@example.test', 'Active'))

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    await wrapper.find('button[aria-label="User deaktivieren"]').trigger('click')
    await flushPromises()

    expect(disableManagedUserMock).toHaveBeenCalledWith(2)
    expect(wrapper.text()).toContain('Deaktiviert')

    await wrapper.find('button[aria-label="User aktivieren"]').trigger('click')
    await flushPromises()

    expect(enableManagedUserMock).toHaveBeenCalledWith(2)
  })

  it('updates managed user server roles', async () => {
    mockServerPanel()
    getManagedUsersMock.mockResolvedValue({
      users: [managedUser(2, 'Member', 'member@example.test', 'Active')]
    })
    updateManagedUserRoleMock.mockResolvedValue({
      ...managedUser(2, 'Member', 'member@example.test', 'Active'),
      serverRole: 'ServerOwner'
    })

    const wrapper = mount(ServerSettingsPanel, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    await wrapper.find('select').setValue('ServerOwner')
    await flushPromises()

    expect(updateManagedUserRoleMock).toHaveBeenCalledWith(2, 'ServerOwner')
    expect(wrapper.text()).toContain('Server-Rolle wurde gespeichert.')
  })
})

function serverSettings(overrides: Partial<ServerSettingsResponse> = {}): ServerSettingsResponse {
  return {
    displayName: 'Server',
    userManagementAvailable: true,
    license: {
      edition: 'COMMUNITY',
      license: 'AGPL-3.0-only',
      sourceUrl: 'https://github.com/raddatzk/veyq',
      noticeUrl: 'https://github.com/raddatzk/veyq/blob/main/NOTICE',
      trademarkPolicyUrl: 'https://github.com/raddatzk/veyq/blob/main/docs/legal/trademark-policy.md',
      commercialBoundaryUrl: 'https://github.com/raddatzk/veyq/blob/main/docs/legal/commercial-boundary.md',
      version: 'v1.0.0'
    },
    capabilities: [],
    ...overrides
  }
}

function primeStubs() {
  return {
    Message: {
      template: '<div><slot /></div>'
    },
    ProgressSpinner: {
      template: '<span />'
    },
    Select: {
      template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>',
      props: ['modelValue', 'options']
    },
    ManagementList: {
      template: '<div><slot /></div>'
    },
    Button: {
      template: '<button :aria-label="ariaLabel" @click="$emit(\'click\')"><slot />{{ label }}</button>',
      props: ['ariaLabel', 'label']
    }
  }
}

function managedUser(userId: number, displayName: string, email: string, status: 'Active' | 'Pending' | 'Disabled' | 'Deleted') {
  return {
    userId,
    displayName,
    email,
    serverRole: 'ServerMember' as const,
    status,
    tenants: []
  }
}
