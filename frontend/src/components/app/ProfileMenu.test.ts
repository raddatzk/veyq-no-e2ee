import { shallowMount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../authState'
import ProfileMenu from './ProfileMenu.vue'

const push = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push })
}))

describe('ProfileMenu', () => {
  afterEach(() => {
    push.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('shows user settings for authenticated users', () => {
    authState.isAuthenticated = true
    authState.userLabel = 'Member'
    authState.user = {
      userId: 1,
      email: 'member@example.com',
      displayName: 'Member',
      permalink: 'member',
      serverRole: 'ServerMember',
      passkeyCount: 0
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    const labels = menuLabels(wrapper)
    expect(labels).toContain('Meine Einstellungen')
    expect(labels).not.toContain('Server Einstellungen')

    menuItem(wrapper, 'Meine Einstellungen').command()
    expect(push).toHaveBeenCalledWith('/~me')
  })

  it('shows server settings only for server owners', () => {
    authState.isAuthenticated = true
    authState.userLabel = 'Owner'
    authState.user = {
      userId: 1,
      email: 'owner@example.com',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 0
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    expect(menuLabels(wrapper)).toContain('Server Einstellungen')
    menuItem(wrapper, 'Server Einstellungen').command()
    expect(push).toHaveBeenCalledWith('/settings')
  })
})

function menuLabels(wrapper: ReturnType<typeof shallowMount>) {
  return wrapper
    .findComponent({ name: 'Menu' })
    .props('model')
    .filter((item: { label?: string }) => item.label)
    .map((item: { label: string }) => item.label)
}

function menuItem(wrapper: ReturnType<typeof shallowMount>, label: string) {
  return wrapper
    .findComponent({ name: 'Menu' })
    .props('model')
    .find((item: { label?: string }) => item.label === label) as { command: () => void }
}

function primeVueStubs() {
  return {
    Avatar: { name: 'Avatar', template: '<div />' },
    Menu: { name: 'Menu', props: ['model'], template: '<div />' }
  }
}
