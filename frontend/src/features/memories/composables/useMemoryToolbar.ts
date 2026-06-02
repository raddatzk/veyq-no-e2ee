import { type ComputedRef, onBeforeUnmount, type Ref, watch } from 'vue'
import type { MenuItem } from 'primevue/menuitem'
import { clearWorkspaceToolbar, setWorkspaceToolbar } from '../../../composables/useWorkspaceToolbar'
import type { Memory, MemoryNavigationResponse } from '../api'
import { i18n } from '../../../i18n'
import type { MemoryRouteBreadcrumb } from './useMemoryRouteData'

type ToolbarOptions = {
  navigation: Ref<MemoryNavigationResponse | null>
  memory: Ref<Memory | null>
  memoryBreadcrumbs: Ref<MemoryRouteBreadcrumb[]>
  loading: Ref<boolean>
  tenantName: ComputedRef<string>
  tenantLabel: ComputedRef<string>
  routePath: ComputedRef<string>
  openContextSettings: () => void
  navigateToPath: (path: string) => void
}

export function useMemoryToolbar(options: ToolbarOptions) {
  let lastContextKey = ''

  watch(
    [
      options.navigation,
      options.memory,
      options.memoryBreadcrumbs,
      options.loading,
      options.tenantName,
      options.tenantLabel,
      options.routePath,
      i18n.global.locale
    ],
    updateToolbar,
    { immediate: true }
  )

  onBeforeUnmount(() => {
    clearWorkspaceToolbar()
  })

  function updateToolbar() {
    if (!options.tenantName.value) {
      lastContextKey = ''
      clearWorkspaceToolbar()
      return
    }

    const contextKey = `${options.tenantName.value}:${options.routePath.value}`
    if (contextKey !== lastContextKey) {
      lastContextKey = contextKey
      if (!options.memory.value && !options.navigation.value) clearWorkspaceToolbar()
    }

    if (options.memory.value) {
      const memory = options.memory.value
      setWorkspaceToolbar(
        memoryBreadcrumbs(
          memory,
          options.tenantLabel.value,
          options.navigateToPath,
          options.memoryBreadcrumbs.value
        ),
        []
      )
      return
    }

    if (!options.navigation.value) {
      if (options.loading.value) return
      clearWorkspaceToolbar()
      return
    }

    const breadcrumbs = options.navigation.value.breadcrumbs.map((breadcrumb) => ({
      label: breadcrumb.label,
      command: () => options.navigateToPath(breadcrumb.path)
    }))

    setWorkspaceToolbar(breadcrumbs, [
      {
        key: 'settings',
        label: i18n.global.t('settings.title'),
        icon: 'pi pi-cog',
        severity: 'secondary',
        outlined: true,
        command: options.openContextSettings
      }
    ])
  }
}

export function memoryBreadcrumbs(
  memory: Memory,
  tenantLabel: string,
  navigateToPath: (path: string) => void,
  resolvedBreadcrumbs: MemoryRouteBreadcrumb[] = []
): MenuItem[] {
  const parts = memory.path.split('/').filter(Boolean)
  const labelsByPath = new Map(resolvedBreadcrumbs.map((breadcrumb) => [breadcrumb.path, breadcrumb.label]))
  return [
    {
      label: tenantLabel,
      command: () => navigateToPath('/')
    },
    ...parts.map((part, index) => {
      const path = `/${parts.slice(0, index + 1).join('/')}`
      return {
        label:
          labelsByPath.get(path) ||
          (index === parts.length - 1 ? memory.displayName : breadcrumbSegmentLabel(part, memory)),
        command: () => navigateToPath(path)
      }
    })
  ]
}

function breadcrumbSegmentLabel(segment: string, memory: Memory): string {
  if (segment.startsWith('@')) return segment
  if (segment.startsWith('~') && memory.scope?.type === 'USER') return i18n.global.t('navigation.personal')
  return segment
}
