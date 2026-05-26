import { describe, expect, it, vi } from 'vitest'
import { memoryBreadcrumbs } from './useMemoryToolbar'
import type { Memory } from '../api'

describe('memoryBreadcrumbs', () => {
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
