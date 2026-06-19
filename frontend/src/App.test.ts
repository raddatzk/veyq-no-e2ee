import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setUnlocked } from './app/session'
import { login } from './features/account/login/login'
import App from './App.vue'

vi.mock('./features/account/login/login', () => ({
  login: vi.fn(async () => ({
    sessionToken: 'remembered-token',
    user: { id: 'u', email: 'kevin@example.com', displayName: 'Kevin', serverRole: 'ServerMember' },
    identity: {},
    identityPublicKey: { suiteId: 'v1', kemPublicKey: '', signPublicKey: '' },
    masterKey: new Uint8Array(32),
  })),
}))
vi.mock('./app/home/HomeView.vue', () => ({
  default: { name: 'HomeView', template: '<main>Home</main>' },
}))

// Drives the slice-1 web auth machine: on boot, /api/setup decides setup (first run) vs login.
// Web is same-origin, so there is no instance-URL step.
function mockSetup(setupRequired: boolean) {
  return vi.fn(() =>
    Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(''), json: () => Promise.resolve({ setupRequired, suites: ['v1'] }) }),
  )
}
const flush = () => new Promise((r) => setTimeout(r))

beforeEach(() => {
  localStorage.clear()
  setUnlocked(null)
  vi.mocked(login).mockClear()
})
afterEach(() => {
  vi.unstubAllGlobals()
  setUnlocked(null)
})

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

  it('uses a remembered login on boot after reload', async () => {
    localStorage.setItem('veyq.rememberedLogin', JSON.stringify({ email: 'kevin@example.com', password: 'pw', secretKey: 'sk' }))
    vi.stubGlobal('fetch', mockSetup(false))

    const wrapper = mount(App)
    await flush()
    await flush()

    expect(login).toHaveBeenCalledWith('', 'kevin@example.com', 'pw', 'sk')
    expect(wrapper.text()).toContain('Home')
    expect(wrapper.text()).not.toContain('Anmelden')
  })

  it('an unreachable instance shows an error state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('down'))))
    const wrapper = mount(App)
    await flush()
    expect(wrapper.text()).toContain('Instanz nicht erreichbar')
  })
})
