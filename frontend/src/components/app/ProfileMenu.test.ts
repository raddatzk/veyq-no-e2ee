import { shallowMount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../authState'
import { i18n } from '../../i18n'
import { useThemeState } from '../../shared/state/useThemeState'
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
      passkeyCount: 0,
      hasPassword: true
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    const labels = menuLabels(wrapper)
    expect(labels).toContain('Meine Einstellungen')
    expect(labels).toContain('Sprache')
    expect(labels).toContain('Darstellung')
    expect(labels).not.toContain('Einstellungen')

    menuItem(wrapper, 'Meine Einstellungen').command?.()
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
      passkeyCount: 0,
      hasPassword: true
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    expect(menuLabels(wrapper)).toContain('Einstellungen')
    menuItem(wrapper, 'Einstellungen').command?.()
    expect(push).toHaveBeenCalledWith('/settings')
  })

  it('shows language choices and switches locale', () => {
    authState.isAuthenticated = true
    authState.userLabel = 'Member'
    authState.user = {
      userId: 1,
      email: 'member@example.com',
      displayName: 'Member',
      permalink: 'member',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    const languageItem = menuItem(wrapper, 'Sprache') as { items: Array<{ label: string; command: () => void }> }
    expect(languageItem.items.map((item) => item.label)).toEqual(['Deutsch', 'Englisch'])

    languageItem.items.find((item) => item.label === 'Englisch')!.command()

    expect(i18n.global.locale.value).toBe('en')
  })

  it('shows theme choices and stores the selected theme', () => {
    authState.isAuthenticated = true
    authState.userLabel = 'Member'
    authState.user = {
      userId: 1,
      email: 'member@example.com',
      displayName: 'Member',
      permalink: 'member',
      serverRole: 'ServerMember',
      passkeyCount: 0,
      hasPassword: true
    }

    const wrapper = shallowMount(ProfileMenu, {
      global: { stubs: primeVueStubs() }
    })

    const themeItem = menuItem(wrapper, 'Darstellung') as { items: Array<{ label: string; command: () => void }> }
    expect(themeItem.items.map((item) => item.label)).toEqual(['System', 'Hell', 'Dunkel'])

    themeItem.items.find((item) => item.label === 'Dunkel')!.command()

    expect(useThemeState().themePreference.value).toBe('dark')
    expect(localStorage.getItem('veyq:theme')).toBe('dark')
  })
})

function menuLabels(wrapper: ReturnType<typeof shallowMount>) {
  return wrapper
    .findComponent({ name: 'TieredMenu' })
    .props('model')
    .filter((item: { label?: string }) => item.label)
    .map((item: { label: string }) => item.label)
}

function menuItem(wrapper: ReturnType<typeof shallowMount>, label: string) {
  return wrapper
    .findComponent({ name: 'TieredMenu' })
    .props('model')
    .find((item: { label?: string }) => item.label === label) as { command?: () => void }
}

function primeVueStubs() {
  return {
    Avatar: { name: 'Avatar', template: '<div />' },
    TieredMenu: { name: 'TieredMenu', props: ['model'], template: '<div />' }
  }
}
