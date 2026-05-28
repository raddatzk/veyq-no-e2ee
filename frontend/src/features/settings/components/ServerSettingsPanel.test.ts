import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  getServerNotifications,
  getServerSettings,
  getServerUpdateEvents,
  type ServerSettingsResponse
} from '../api'
import ServerSettingsPanel from './ServerSettingsPanel.vue'

vi.mock('../api', () => ({
  getServerSettings: vi.fn(),
  getServerNotifications: vi.fn(),
  getServerUpdateEvents: vi.fn()
}))

const getServerSettingsMock = vi.mocked(getServerSettings)
const getServerNotificationsMock = vi.mocked(getServerNotifications)
const getServerUpdateEventsMock = vi.mocked(getServerUpdateEvents)

describe('ServerSettingsPanel', () => {
  afterEach(() => {
    vi.resetAllMocks()
  })

  function mockServerPanel(settings = serverSettings()) {
    getServerSettingsMock.mockResolvedValue(settings)
    getServerNotificationsMock.mockResolvedValue([])
    getServerUpdateEventsMock.mockResolvedValue([])
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
    expect(wrapper.text()).not.toContain('veyq unterstützen')
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
    }
  }
}
