import { beforeEach, describe, expect, it, vi } from 'vitest'

// Mock the boot dependencies: instance status, login, theme. The remembered-login storage primitive itself is
// real (covered by rememberedLogin.test.ts) — we drive it through localStorage to exercise the boot replay.
const getSetupStatus = vi.hoisted(() => vi.fn())
const login = vi.hoisted(() => vi.fn())

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus }))
vi.mock('../features/account/login/login', () => ({ login }))
vi.mock('../shared/state/useThemeState', () => ({ initializeTheme: vi.fn() }))

import { boot, bootState } from './boot'
import { serverState } from '../serverState'
import { session, setUnlocked } from './session'
import { authState } from '../authState'
import { saveRememberedLogin } from '../features/account/rememberedLogin/rememberedLogin'

const fakeSession = { sessionToken: 'tok', user: { id: 'u1', email: 'kevin@example.com' }, identity: {}, identityPublicKey: {}, masterKey: new Uint8Array() } as never

describe('boot (app-shell instance routing)', () => {
  beforeEach(() => {
    localStorage.clear()
    setUnlocked(null)
    serverState.setupCompleted = false
    bootState.unreachable = false
    authState.loading = true
    getSetupStatus.mockReset()
    login.mockReset()
  })

  it('routes a fresh instance to setup (setupCompleted stays false, not unreachable)', async () => {
    getSetupStatus.mockResolvedValue({ setupRequired: true, suites: [] })

    await boot()

    expect(serverState.setupCompleted).toBe(false)
    expect(bootState.unreachable).toBe(false)
    expect(authState.loading).toBe(false)
  })

  it('routes an already-set-up instance to login (setupCompleted true, still locked)', async () => {
    getSetupStatus.mockResolvedValue({ setupRequired: false, suites: [] })

    await boot()

    expect(serverState.setupCompleted).toBe(true)
    expect(session.unlocked).toBeNull()
    expect(bootState.unreachable).toBe(false)
  })

  it('surfaces a clear error state when the instance is unreachable on boot', async () => {
    getSetupStatus.mockRejectedValue(new Error('network down'))

    await boot()

    expect(bootState.unreachable).toBe(true)
    expect(session.unlocked).toBeNull()
    expect(authState.loading).toBe(false)
  })

  it('replays a remembered login during boot so the user lands directly in the app', async () => {
    getSetupStatus.mockResolvedValue({ setupRequired: false, suites: [] })
    login.mockResolvedValue(fakeSession)
    saveRememberedLogin({ email: 'kevin@example.com', password: 'pw', secretKey: 'veyq-secret' })

    await boot()

    expect(login).toHaveBeenCalledWith(session.apiBase, 'kevin@example.com', 'pw', 'veyq-secret')
    expect(session.unlocked).not.toBeNull()
    expect(session.unlocked?.sessionToken).toBe('tok')
  })

  it('drops an invalid remembered login on boot and stays locked', async () => {
    getSetupStatus.mockResolvedValue({ setupRequired: false, suites: [] })
    login.mockRejectedValue(new Error('bad credentials'))
    saveRememberedLogin({ email: 'kevin@example.com', password: 'pw', secretKey: 'veyq-secret' })

    await boot()

    expect(session.unlocked).toBeNull()
    expect(localStorage.getItem('veyq.rememberedLogin')).toBeNull()
  })
})
