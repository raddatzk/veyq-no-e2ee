import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getServerSettings, type ServerSettingsResponse } from '../../features/settings/api'
import SupportNotice from './SupportNotice.vue'

vi.mock('../../features/settings/api', () => ({
  getServerSettings: vi.fn()
}))

const getServerSettingsMock = vi.mocked(getServerSettings)

describe('SupportNotice', () => {
  afterEach(() => {
    vi.resetAllMocks()
    window.localStorage.clear()
  })

  it('stays hidden when the server omits the support link', async () => {
    getServerSettingsMock.mockResolvedValue(serverSettings())

    const wrapper = mount(SupportNotice, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toBe('')
  })

  it('shows support notice and stores dismiss state locally', async () => {
    getServerSettingsMock.mockResolvedValue(
      serverSettings({ support: { url: 'https://support.example.test/veyq' } })
    )

    const wrapper = mount(SupportNotice, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('veyq ist freie Software')
    expect(wrapper.find('a[href="https://support.example.test/veyq"]').exists()).toBe(true)

    await wrapper.find('button').trigger('click')

    expect(window.localStorage.getItem('veyq:support-notice-dismissed:v1.0.0')).toBeTruthy()
    expect(wrapper.text()).toBe('')
  })

  it('shows support notice again for a new version', async () => {
    window.localStorage.setItem('veyq:support-notice-dismissed:v1.0.0', String(Date.now()))
    getServerSettingsMock.mockResolvedValue(
      serverSettings({
        license: {
          ...serverSettings().license,
          version: 'v1.0.1'
        },
        support: { url: 'https://support.example.test/veyq' }
      })
    )

    const wrapper = mount(SupportNotice, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('veyq ist freie Software')
  })

  it('shows support notice again after ninety days', async () => {
    const ninetyOneDaysAgo = Date.now() - 91 * 24 * 60 * 60 * 1000
    window.localStorage.setItem('veyq:support-notice-dismissed:v1.0.0', String(ninetyOneDaysAgo))
    getServerSettingsMock.mockResolvedValue(
      serverSettings({ support: { url: 'https://support.example.test/veyq' } })
    )

    const wrapper = mount(SupportNotice, {
      global: { stubs: primeStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('veyq ist freie Software')
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
    Button: {
      props: ['label'],
      emits: ['click'],
      template: '<button type="button" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    }
  }
}
