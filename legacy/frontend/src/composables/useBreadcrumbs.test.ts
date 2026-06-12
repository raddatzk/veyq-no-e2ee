import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import { authState, type TenantSummary } from '../authState'
import { useBreadcrumbs } from './useBreadcrumbs'

const BreadcrumbProbe = defineComponent({
  setup() {
    return useBreadcrumbs()
  },
  template: '<button @click="fallbackBreadcrumb.command">{{ fallbackBreadcrumb.label }}</button>'
})

describe('useBreadcrumbs', () => {
  afterEach(() => {
    authState.tenants = []
    authState.activeTenantName = ''
  })

  it('uses only the tenant fallback breadcrumb for deep tenant routes', async () => {
    const router = testRouter()
    authState.tenants = [tenant('friends', 'Friends')]
    authState.activeTenantName = 'friends'
    await router.push('/@family/trip/settings')
    await router.isReady()
    const wrapper = mount(BreadcrumbProbe, { global: { plugins: [router] } })

    expect(wrapper.text()).toBe('Friends')

    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('falls back to veyq when no active tenant is known', async () => {
    const router = testRouter()
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(BreadcrumbProbe, { global: { plugins: [router] } })

    expect(wrapper.text()).toBe('veyq')

    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/')
  })
})

function testRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
}

function tenant(name: string, displayName: string): TenantSummary {
  return {
    id: 1,
    name,
    displayName,
    domain: `${name}.example.test`,
    role: 'TenantOwner',
    admins: []
  }
}
