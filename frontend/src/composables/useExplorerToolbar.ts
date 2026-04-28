import { reactive } from 'vue'
import type { MenuItem } from 'primevue/menuitem'

export type ExplorerToolbarAction = {
  key: string
  label: string
  icon: string
  disabled?: boolean
  severity?: 'secondary' | 'success' | 'info' | 'warn' | 'danger' | 'help' | 'contrast'
  outlined?: boolean
  command: () => void
}

type ExplorerToolbarState = {
  active: boolean
  breadcrumbs: MenuItem[]
  actions: ExplorerToolbarAction[]
}

export const explorerToolbar = reactive<ExplorerToolbarState>({
  active: false,
  breadcrumbs: [],
  actions: []
})

export function setExplorerToolbar(breadcrumbs: MenuItem[], actions: ExplorerToolbarAction[]) {
  explorerToolbar.active = true
  explorerToolbar.breadcrumbs = breadcrumbs
  explorerToolbar.actions = actions
}

export function clearExplorerToolbar() {
  explorerToolbar.active = false
  explorerToolbar.breadcrumbs = []
  explorerToolbar.actions = []
}
