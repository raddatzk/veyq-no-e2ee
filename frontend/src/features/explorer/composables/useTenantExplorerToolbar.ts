import { type ComputedRef, onBeforeUnmount, type Ref, watch } from 'vue'
import type { ExplorerResponse, LibraryItemType } from '../api'
import { clearExplorerToolbar, setExplorerToolbar } from '../../../composables/useExplorerToolbar'

type ToolbarOptions = {
  explorer: Ref<ExplorerResponse | null>
  tenantName: ComputedRef<string>
  tenantTitle: ComputedRef<string>
  isAlbumContext: ComputedRef<boolean>
  isLibraryContext: ComputedRef<boolean>
  contextType: ComputedRef<string>
  navigateToPath: (path: string) => void
  openCreate: (type: LibraryItemType) => void
  openContextSettings: () => void
  openUpload: () => void
}

export function useTenantExplorerToolbar(options: ToolbarOptions) {
  watch(
    [
      options.explorer,
      options.tenantName,
      options.tenantTitle,
      options.isAlbumContext,
      options.isLibraryContext,
      options.contextType
    ],
    updateToolbar,
    { immediate: true }
  )

  onBeforeUnmount(() => {
    clearExplorerToolbar()
  })

  function updateToolbar() {
    if (!options.explorer.value || !options.tenantName.value) {
      clearExplorerToolbar()
      return
    }

    const breadcrumbs = options.explorer.value.breadcrumbs.map((breadcrumb) => ({
      label: breadcrumb.label,
      command: () => options.navigateToPath(breadcrumb.path)
    }))

    setExplorerToolbar(breadcrumbs, [
      ...(!options.isAlbumContext.value
        ? [
            {
              key: 'create-folder',
              label: 'Ordner',
              icon: 'pi pi-folder-plus',
              command: () => options.openCreate('folder')
            },
            {
              key: 'create-album',
              label: 'Album',
              icon: 'pi pi-images',
              command: () => options.openCreate('album')
            }
          ]
        : []),
      ...(options.isAlbumContext.value
        ? [
            {
              key: 'upload',
              label: 'Hochladen',
              icon: 'pi pi-upload',
              command: options.openUpload
            }
          ]
        : []),
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
