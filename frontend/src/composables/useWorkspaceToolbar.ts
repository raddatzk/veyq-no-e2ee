import { reactive } from 'vue'
import type { MenuItem } from 'primevue/menuitem'

export type WorkspaceToolbarAction = {
  key: string
  label: string
  icon: string
  severity?: 'secondary' | 'success' | 'info' | 'warn' | 'danger' | 'help' | 'contrast'
  outlined?: boolean
  command: () => void
}

type WorkspaceToolbarState = {
  active: boolean
  breadcrumbs: MenuItem[]
  actions: WorkspaceToolbarAction[]
}

export const workspaceToolbar = reactive<WorkspaceToolbarState>({
  active: false,
  breadcrumbs: [],
  actions: []
})

export function setWorkspaceToolbar(breadcrumbs: MenuItem[], actions: WorkspaceToolbarAction[]) {
  workspaceToolbar.active = true
  workspaceToolbar.breadcrumbs = breadcrumbs
  workspaceToolbar.actions = actions
}

export function clearWorkspaceToolbar() {
  workspaceToolbar.active = false
  workspaceToolbar.breadcrumbs = []
  workspaceToolbar.actions = []
}
