import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsOverlay from './SettingsOverlay.vue'
import { session, setUnlocked } from '../session'
import { updateProfile } from '../../features/account/login/login'

vi.mock('../../features/account/login/login', () => ({
  logout: vi.fn(async () => undefined),
  updateProfile: vi.fn(async () => ({ id: 'u', email: 'k@example.com', displayName: 'New Name', serverRole: 'ServerMember' })),
}))
vi.mock('../../features/keys/rotateIdentity', () => ({ rotateIdentity: vi.fn(async () => undefined) }))
vi.mock('../../features/account/deviceUnlock/deviceUnlock', () => ({
  deviceUnlockEnabled: vi.fn(() => false),
  disableDeviceUnlock: vi.fn(),
}))

beforeEach(() => {
  setUnlocked({ sessionToken: 'token', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerMember' } } as never)
  session.apiBase = ''
  vi.mocked(updateProfile).mockClear()
})

describe('SettingsOverlay profile', () => {
  it('updates the display name and current session user', async () => {
    const wrapper = mount(SettingsOverlay)

    const input = wrapper.find('.settings-field input')
    await input.setValue('New Name')
    await wrapper.find('form.settings-profile').trigger('submit')
    await flushPromises()

    expect(updateProfile).toHaveBeenCalledWith('', 'token', 'New Name')
    expect(session.unlocked?.user.displayName).toBe('New Name')
    expect(wrapper.text()).toContain('Gespeichert.')
  })
})
