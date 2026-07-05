// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

// The tenant list is a network call (onMounted) — hand it a single seeded tenant.
vi.mock('../../features/spaces/scopes', () => ({
  listMyTenants: vi.fn().mockResolvedValue([
    { tenantId: 't-1', name: 'Familie Muster', domain: '127.0.0.1', role: 'OWNER' },
  ]),
}))

import AppSidebar from './AppSidebar.vue'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      shell: { spaces: 'Spaces', personal: 'Personal', shared: 'Shared', favorites: 'Favorites', menu: 'Navigation', menuOpen: 'Open navigation' },
      members: { awaitingAccess: 'Awaiting access' },
    },
  },
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
}

async function mountAt(hash: string) {
  const router = makeRouter()
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const wrapper = mount(AppSidebar, {
    global: { plugins: [router, i18n], stubs: { ProfileMenu: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  clearActiveSpace()
  activeSpace.tenantId = 't-1' // as if the tenant space is the active one
})
afterEach(() => clearActiveSpace())

describe('AppSidebar space list', () => {
  it('lists the Tenant space (labelled with the tenant name) beside Personal', async () => {
    const wrapper = await mountAt('')
    const tenant = wrapper.get('.sidebar-tenant-space-link')
    const personal = wrapper.get('.sidebar-personal-link')
    expect(tenant.text()).toContain('Familie Muster')
    expect(personal.text()).toContain('Personal')
  })

  it('highlights the Tenant space on the token-less home', async () => {
    const wrapper = await mountAt('')
    expect(wrapper.get('.sidebar-tenant-space-link').classes()).toContain('router-link-active')
    expect(wrapper.get('.sidebar-personal-link').classes()).not.toContain('router-link-active')
  })

  it('highlights Personal on the `~me` fragment', async () => {
    const wrapper = await mountAt('#~me')
    expect(wrapper.get('.sidebar-personal-link').classes()).toContain('router-link-active')
    expect(wrapper.get('.sidebar-tenant-space-link').classes()).not.toContain('router-link-active')
  })

  it('surfaces "awaiting access" on the active tenant when the caller holds no grant yet (cr-0077)', async () => {
    activeSpace.tenantUnavailable = true // a grant-less member fell back to Personal
    const wrapper = await mountAt('')
    const awaiting = wrapper.find('.sidebar-tenant-awaiting')
    expect(awaiting.exists()).toBe(true)
    expect(awaiting.text()).toContain('Awaiting access')
  })

  it('shows the domain (no awaiting badge) once the grant is delivered', async () => {
    activeSpace.tenantUnavailable = false
    const wrapper = await mountAt('')
    expect(wrapper.find('.sidebar-tenant-awaiting').exists()).toBe(false)
  })

  // The mobile bottom-sheet nav (cr-0084): the inline nav is CSS-hidden below the compact threshold, so the
  // hamburger opens a sheet with the full navigation (tenant switch + Shared + Personal).
  it('opens a mobile nav sheet with the tenant switch, Shared and Personal', async () => {
    // Stub the teleporting PrimeVue Dialog with a pass-through that renders its slot when visible.
    const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dialog-stub"><slot /></div>' }
    const router = makeRouter()
    await router.replace({ name: 'home', hash: '' })
    await router.isReady()
    const wrapper = mount(AppSidebar, {
      global: { plugins: [router, i18n], stubs: { ProfileMenu: true, Dialog: DialogStub } },
    })
    await flushPromises()

    expect(wrapper.find('.mobile-nav-sheet').exists()).toBe(false) // closed initially
    await wrapper.get('.mobile-navigation-button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('.mobile-nav-item').map((i) => i.text())
    expect(items.some((t) => t.includes('Familie Muster'))).toBe(true) // tenant switch
    expect(items.some((t) => t.includes('Shared'))).toBe(true)
    expect(items.some((t) => t.includes('Personal'))).toBe(true)
  })
})
