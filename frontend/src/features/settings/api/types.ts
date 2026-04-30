import type { ExplorerLocationKind } from '../../explorer/api'

export type SettingsLocationKind = ExplorerLocationKind | 'SETTINGS'

export type SettingsLocation = {
  kind: SettingsLocationKind
  path: string
  displayName: string
  label: string
}

export type SettingsBreadcrumb = {
  kind: SettingsLocationKind
  path: string
  displayName: string
  label: string
}

export type SettingsResponse = {
  location: SettingsLocation
  subject: SettingsLocation
  breadcrumbs: SettingsBreadcrumb[]
}
