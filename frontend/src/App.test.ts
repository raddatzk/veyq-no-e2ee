import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

// Drives the slice-1 web auth machine: on boot, /api/setup decides setup (first run) vs login.
// Web is same-origin, so there is no instance-URL step.
function mockSetup(setupRequired: boolean) {
  return vi.fn(() =>
    Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(''), json: () => Promise.resolve({ setupRequired, suites: ['v1'] }) }),
  )
}
const flush = () => new Promise((r) => setTimeout(r))

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('web auth flow (slice 1)', () => {
  it('an already-setup instance boots straight into login (no instance URL)', async () => {
    vi.stubGlobal('fetch', mockSetup(false))
    const wrapper = mount(App)
    await flush()
    expect(wrapper.text()).toContain('Anmelden')
    expect(wrapper.text()).toContain('Secret Key')
    expect(wrapper.text()).not.toContain('Instanz-URL')
  })

  it('a fresh instance boots into setup', async () => {
    vi.stubGlobal('fetch', mockSetup(true))
    const wrapper = mount(App)
    await flush()
    expect(wrapper.text()).toContain('Server einrichten')
  })

  it('an unreachable instance shows an error state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('down'))))
    const wrapper = mount(App)
    await flush()
    expect(wrapper.text()).toContain('Instanz nicht erreichbar')
  })
})
