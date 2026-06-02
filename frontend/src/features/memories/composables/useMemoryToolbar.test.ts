import { computed, effectScope, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearWorkspaceToolbar, workspaceToolbar } from '../../../composables/useWorkspaceToolbar'
import { memoryBreadcrumbs, useMemoryToolbar } from './useMemoryToolbar'
import type { MemoryRouteBreadcrumb } from './useMemoryRouteData'
import type { Memory, MemoryNavigationResponse } from '../api'

describe('memoryBreadcrumbs', () => {
  afterEach(() => {
    clearWorkspaceToolbar()
  })

  it('includes the tenant and parent path segments before the memory label', () => {
    const navigateToPath = vi.fn()
    const breadcrumbs = memoryBreadcrumbs(
      memory({
        displayName: 'Hochzeit Lisa & Kevin',
        path: '/@test/feiern/hochzeit-lisa-kevin',
        scope: { type: 'GROUP', groupName: 'test' }
      }),
      'Friends',
      navigateToPath
    )

    expect(breadcrumbs.map((breadcrumb) => breadcrumb.label)).toEqual([
      'Friends',
      '@test',
      'feiern',
      'Hochzeit Lisa & Kevin'
    ])

    breadcrumbs[2].command?.({ originalEvent: new Event('click'), item: breadcrumbs[2] })
    expect(navigateToPath).toHaveBeenCalledWith('/@test/feiern')
  })

  it('uses resolved display names for parent memories', () => {
    const navigateToPath = vi.fn()
    const breadcrumbs = memoryBreadcrumbs(
      memory({
        displayName: 'Albanien',
        path: '/korfubaniens/albanien'
      }),
      'Friends',
      navigateToPath,
      [
        { path: '/korfubaniens', label: 'Korfubanien' },
        { path: '/korfubaniens/albanien', label: 'Albanien' }
      ]
    )

    expect(breadcrumbs.map((breadcrumb) => breadcrumb.label)).toEqual([
      'Friends',
      'Korfubanien',
      'Albanien'
    ])
  })

  it('clears stale settings actions while a new tenant context is loading', async () => {
    const tenantName = ref('friends')
    const routePath = ref('/')
    const navigation = ref<MemoryNavigationResponse | null>(navigationResponse('Friends'))
    const memoryRef = ref<Memory | null>(null)
    const memoryBreadcrumbsRef = ref<MemoryRouteBreadcrumb[]>([])
    const loading = ref(false)
    const openContextSettings = vi.fn()
    const navigateToPath = vi.fn()
    const scope = effectScope()

    scope.run(() => {
      useMemoryToolbar({
        navigation,
        memory: memoryRef,
        memoryBreadcrumbs: memoryBreadcrumbsRef,
        loading,
        tenantName: computed(() => tenantName.value),
        tenantLabel: computed(() => tenantName.value),
        routePath: computed(() => routePath.value),
        openContextSettings,
        navigateToPath
      })
    })

    expect(workspaceToolbar.actions.map((action) => action.key)).toEqual(['settings'])

    tenantName.value = 'family'
    loading.value = true
    navigation.value = null
    await nextTick()

    expect(workspaceToolbar.active).toBe(false)
    expect(workspaceToolbar.actions).toEqual([])

    scope.stop()
  })

  it('waits for resolved memory breadcrumb labels before rendering memory toolbar breadcrumbs', async () => {
    const tenantName = ref('friends')
    const routePath = ref('/ostern/ostern-2026')
    const navigation = ref<MemoryNavigationResponse | null>(null)
    const memoryRef = ref<Memory | null>(memory({
      displayName: 'Ostern 2026',
      path: '/ostern/ostern-2026'
    }))
    const memoryBreadcrumbsRef = ref<MemoryRouteBreadcrumb[]>([])
    const loading = ref(false)
    const openContextSettings = vi.fn()
    const navigateToPath = vi.fn()
    const scope = effectScope()

    scope.run(() => {
      useMemoryToolbar({
        navigation,
        memory: memoryRef,
        memoryBreadcrumbs: memoryBreadcrumbsRef,
        loading,
        tenantName: computed(() => tenantName.value),
        tenantLabel: computed(() => tenantName.value),
        routePath: computed(() => routePath.value),
        openContextSettings,
        navigateToPath
      })
    })

    expect(workspaceToolbar.active).toBe(false)

    memoryBreadcrumbsRef.value = [
      { path: '/ostern', label: 'Ostern' },
      { path: '/ostern/ostern-2026', label: 'Ostern 2026' }
    ]
    await nextTick()

    expect(workspaceToolbar.breadcrumbs.map((breadcrumb) => breadcrumb.label)).toEqual([
      'friends',
      'Ostern',
      'Ostern 2026'
    ])

    scope.stop()
  })
})

function memory(overrides: Partial<Memory>): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'memory',
    displayName: 'Memory',
    path: '/memory',
    coverMediaId: null,
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    favorite: false,
    scope: null,
    ...overrides
  }
}

function navigationResponse(label: string): MemoryNavigationResponse {
  return {
    location: { kind: 'TENANT', path: '/', displayName: label },
    breadcrumbs: [{ kind: 'TENANT', path: '/', displayName: label, label }],
    scopeRoots: [],
    favoriteMemories: [],
    folders: []
  }
}
