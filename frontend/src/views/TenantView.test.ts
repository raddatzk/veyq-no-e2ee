import { flushPromises, mount } from '@vue/test-utils'
import { computed, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TenantView from './TenantView.vue'
import { authState } from '../authState'

vi.mock('../features/memories/api', () => ({
  updateMemoryJournal: vi.fn()
}))

vi.mock('../features/memories/composables/useMemoryToolbar', () => ({
  useMemoryToolbar: vi.fn()
}))

vi.mock('../features/memories/composables/useMemoryRouteData', () => ({
  useMemoryRouteData: vi.fn(() => ({
    childMemories: ref([]),
    currentFolderPath: computed(() => '/trip'),
    error: ref(''),
    isMemoryRoute: computed(() => true),
    load: vi.fn(),
    loading: ref(false),
    media: ref([]),
    mediaLoading: ref(false),
    memories: ref([]),
    memory: ref({
      id: 'mem-1',
      parentId: null,
      name: 'trip',
      displayName: 'Trip',
      path: '/trip',
      coverMediaId: null,
      coverUrl: null,
      coverCrop: null,
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      childCount: 1,
      mediaCount: 0,
      favorite: false,
      scope: { type: 'TENANT' }
    }),
    memoryBreadcrumbs: ref([]),
    memoryJournal: ref(null),
    memoryHeader: ref(null),
    playlists: ref([]),
    tracks: ref([]),
    navigation: ref(null)
  }))
}))

describe('TenantView', () => {
  afterEach(() => {
    authState.tenants = []
  })

  it('uses memory tab hashes and drops them when opening a child memory', async () => {
    authState.tenants = [
      {
        id: 1,
        name: 'tenant',
        displayName: 'Tenant',
        domain: 'tenant.example.test',
        role: 'TenantOwner',
        admins: []
      }
    ]
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:tenantName/:pathMatch(.*)*', component: TenantView }]
    })
    await router.push('/tenant/trip#tracks')
    await router.isReady()

    const wrapper = mount(TenantView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="memory-detail"]').attributes('data-active-tab')).toBe('tracks')

    await wrapper.find('[data-testid="select-journal-tab"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/tenant/trip#journal')

    await wrapper.find('[data-testid="open-child-memory"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/tenant/trip/day-1')
  })
})

function viewStubs() {
  return {
    CreateFolderDialog: true,
    CreateMemoryDialog: true,
    MemoryDetail: {
      props: ['activeTab', 'mediaLoading'],
      emits: ['update:activeTab', 'openMemory'],
      template: `
        <section data-testid="memory-detail" :data-active-tab="activeTab">
          <button type="button" data-testid="select-journal-tab" @click="$emit('update:activeTab', 'journal')" />
          <button type="button" data-testid="open-child-memory" @click="$emit('openMemory', { path: '/trip/day-1' })" />
        </section>
      `
    },
    MemoryFolderOverview: true,
    Message: { template: '<div><slot /></div>' },
    ProgressSpinner: true
  }
}
