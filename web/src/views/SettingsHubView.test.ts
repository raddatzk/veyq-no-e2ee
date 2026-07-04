// @vitest-environment happy-dom
// The settings hub (cr-0078, ADR 0022): the section nav is filtered by scope + role, deep links open a section,
// and the split view tracks index vs. detail. The three section panels are stubbed — this tests the shell, not
// the panels (they have their own tests).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('../features/admin/serverRole', () => ({
  serverRole: reactive({ canManage: false, probed: true }),
  probeServerRole: vi.fn(),
}))
vi.mock('../features/settings/AppearancePanel.vue', () => ({ default: { template: '<div class="stub-appearance" />' } }))
vi.mock('../features/spaces/TenantMembersPanel.vue', () => ({ default: { template: '<div class="stub-members" />' } }))
vi.mock('../features/admin/createTenant/TenantAdminPanel.vue', () => ({ default: { template: '<div class="stub-admin" />' } }))

import SettingsHubView from './SettingsHubView.vue'
import { serverRole } from '../features/admin/serverRole'
import { activeSpace, clearActiveSpace } from '../app/activeSpace'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      members: { title: 'Members' },
      settings: {
        title: 'Settings', back: 'Back', personal: 'Personal', tenant: 'Shared space', server: 'Server',
        appearance: 'Appearance', appearanceTitle: 'A', appearanceSub: 's', serverTenants: 'Tenants',
      },
    },
  },
})

async function mountHub(path = '/settings') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings/:section?', name: 'settings', component: { template: '<div />' } }],
  })
  await router.replace(path)
  await router.isReady()
  const wrapper = mount(SettingsHubView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  return wrapper
}

const navText = (w: Awaited<ReturnType<typeof mountHub>>) => w.get('.settings-nav').text()

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
  serverRole.canManage = false
})

describe('SettingsHubView — section filtering by scope × role', () => {
  it('a Server Owner in a tenant sees Personal + Shared space + Server', async () => {
    activeSpace.tenantId = 't-1'
    serverRole.canManage = true
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).toContain('Members')
    expect(nav).toContain('Tenants')
  })

  it('a plain member (not server owner) sees Personal + Shared space, no Server', async () => {
    activeSpace.tenantId = 't-1'
    serverRole.canManage = false
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).toContain('Members')
    expect(nav).not.toContain('Tenants')
  })

  it('with no tenant context and no server role, only Personal shows', async () => {
    activeSpace.tenantId = null
    serverRole.canManage = false
    const nav = navText(await mountHub())
    expect(nav).toContain('Personal')
    expect(nav).not.toContain('Members')
    expect(nav).not.toContain('Tenants')
  })
})

describe('SettingsHubView — split view index vs detail', () => {
  it('wide/no param defaults to the current scope section (tenant members) in the detail', async () => {
    activeSpace.tenantId = 't-1'
    const w = await mountHub('/settings')
    expect(w.find('.stub-members').exists()).toBe(true)
    expect(w.get('.settings-hub').classes()).toContain('is-index')
  })

  it('a deep link opens that section and marks the hub as detail', async () => {
    activeSpace.tenantId = 't-1'
    const w = await mountHub('/settings/appearance')
    expect(w.find('.stub-appearance').exists()).toBe(true)
    expect(w.get('.settings-hub').classes()).toContain('is-detail')
  })

  it('clicking a nav item navigates to its section route', async () => {
    activeSpace.tenantId = 't-1'
    serverRole.canManage = true
    const w = await mountHub('/settings')
    const serverItem = w.findAll('.settings-nav-item').find((b) => b.text().includes('Tenants'))!
    await serverItem.trigger('click')
    await flushPromises()
    expect(w.vm.$route.params.section).toBe('server')
    expect(w.find('.stub-admin').exists()).toBe(true)
  })
})
