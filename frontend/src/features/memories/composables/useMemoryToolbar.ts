import { type ComputedRef, onBeforeUnmount, type Ref, watch } from 'vue'
import type { MenuItem } from 'primevue/menuitem'
import { clearWorkspaceToolbar, setWorkspaceToolbar } from '../../../composables/useWorkspaceToolbar'
import type { Memory, MemoryNavigationResponse } from '../api'

type ToolbarOptions = {
  navigation: Ref<MemoryNavigationResponse | null>
  memory: Ref<Memory | null>
  loading: Ref<boolean>
  tenantName: ComputedRef<string>
  tenantLabel: ComputedRef<string>
  routePath: ComputedRef<string>
  openUpload: () => void
  openContextSettings: () => void
  navigateToPath: (path: string) => void
}

export function useMemoryToolbar(options: ToolbarOptions) {
  watch(
    [
      options.navigation,
      options.memory,
      options.loading,
      options.tenantName,
      options.tenantLabel,
      options.routePath
    ],
    updateToolbar,
    { immediate: true }
  )

  onBeforeUnmount(() => {
    clearWorkspaceToolbar()
  })

  function updateToolbar() {
    if (!options.tenantName.value) {
      clearWorkspaceToolbar()
      return
    }

    if (options.memory.value) {
      const memory = options.memory.value
      setWorkspaceToolbar(
        memoryBreadcrumbs(memory, options.tenantLabel.value, options.navigateToPath),
        [
          {
            key: 'upload',
            label: 'Hochladen',
            icon: 'pi pi-upload',
            command: options.openUpload
          }
        ]
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
        label: 'Einstellungen',
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
  navigateToPath: (path: string) => void
): MenuItem[] {
  const parts = memory.path.split('/').filter(Boolean)
  return [
    {
      label: tenantLabel,
      command: () => navigateToPath('/')
    },
    ...parts.map((part, index) => {
      const path = `/${parts.slice(0, index + 1).join('/')}`
      return {
        label: index === parts.length - 1 ? memory.displayName : breadcrumbSegmentLabel(part, memory),
        command: () => navigateToPath(path)
      }
    })
  ]
}

function breadcrumbSegmentLabel(segment: string, memory: Memory): string {
  if (segment.startsWith('@')) return segment
  if (segment.startsWith('~') && memory.scope?.type === 'USER') return 'Persönlich'
  return segment
}
